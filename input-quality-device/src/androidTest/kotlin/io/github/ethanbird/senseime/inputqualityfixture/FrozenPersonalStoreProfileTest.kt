package io.github.ethanbird.senseime.inputqualityfixture

import android.content.Context
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolates frozen production database read and in-memory construction in a disposable fixture UID. */
@RunWith(AndroidJUnit4::class)
class FrozenPersonalStoreProfileTest {
    @Test fun readAndRestorePersonalRowsFromFrozenProductionApk() {
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")).also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val directory = File(context.getExternalFilesDir(null),"input-quality/$run")
        val policy = JSONObject(File(directory,"protocol.json").readText())
        val apkHash = policy.getString("apkSha256")
        val source = File(directory,"source.apk")
        assertEquals(apkHash,sha(source.readBytes()))
        val apk = File(context.filesDir,"personal-store-$apkHash.apk")
        if (!apk.exists()) FileOutputStream(apk).use { stream ->
            assertTrue(apk.setReadOnly());source.inputStream().use { it.copyTo(stream) }
        }
        assertEquals(apkHash,sha(apk.readBytes()));assertFalse(apk.canWrite())
        val loader = DexClassLoader(apk.absolutePath,context.codeCacheDir.absolutePath,null,context.classLoader.parent)
        val dbClass = loader.loadClass("io.github.ethanbird.senseime.service.UserLexiconDatabase")
        val dbConstructor = dbClass.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }
        val read = dbClass.getDeclaredMethod("loadAll").apply { isAccessible=true }
        val close = dbClass.getMethod("close")
        val memoryClass = loader.loadClass("io.github.ethanbird.senseime.core.MemoryUserLexicon")
        val constructor = memoryClass.constructors.single { it.parameterTypes.lastOrNull()?.name=="kotlin.jvm.internal.DefaultConstructorMarker" }
        assertEquals("Reviewed Kotlin constructor/default-mask shape",10,constructor.parameterCount)
        val recordsField = memoryClass.getDeclaredField("records").apply { isAccessible=true }
        val aliasField = memoryClass.getDeclaredField("aliasIndex").apply { isAccessible=true }
        val fixedClock = if (policy.has("fixedClockMillis")) policy.getLong("fixedClockMillis") else null
        val clockProxy = fixedClock?.let { time ->
            Proxy.newProxyInstance(loader, arrayOf(loader.loadClass("kotlin.jvm.functions.Function0"))) { _, method, _ ->
                check(method.name == "invoke"); time
            }
        }
        val mask = if (fixedClock == null) 254 else 252
        val output=File(directory,"store-load.jsonl")
        assertFalse("New profile output required",output.exists())
        output.writeText(JSONObject().put("type","header").put("apkSha256",apkHash)
            .put("thread",Thread.currentThread().name).put("scope","Frozen APK classes in a separate fixture process; not system IME startup")
            .put("defaultConstructorMask",mask).put("fixedClockMillis",fixedClock).toString()+"\n")
        val steps=policy.getJSONArray("steps")
        for (index in 0 until steps.length()) {
            val step=steps.getJSONObject(index)
            val size=step.getInt("size");val profile=step.getBoolean("profile")
            val seed=File(directory,step.getString("file"))
            assertEquals(step.getString("sha256"),sha(seed.readBytes()))
            // Only this standalone fixture UID owns this database path; the Sense profile is untouched.
            context.deleteDatabase("sense_user_lexicon.db")
            val database=context.getDatabasePath("sense_user_lexicon.db")
            database.parentFile!!.mkdirs();seed.copyTo(database,overwrite=false)
            val handle=dbConstructor.newInstance(context)
            val remote="/data/local/tmp/$run-$index.methods"
            if (profile) {
                val command="am profile start --sampling 1000 --clock-type dual --profiler-output-version 3 ${android.os.Process.myPid()} $remote"
                val start=device.executeShellCommand(command)
                File(directory,"$index-profile-start.txt").writeText(start)
                assertFalse(start.contains("Error") || start.contains("Exception"))
            }
            try {
                val readStart=SystemClock.elapsedRealtimeNanos();val readCpu=Debug.threadCpuTimeNanos()
                val rows=read.invoke(handle) as List<*>
                val readCpuNs=Debug.threadCpuTimeNanos()-readCpu;val readWallNs=SystemClock.elapsedRealtimeNanos()-readStart
                assertEquals(size,rows.size)
                val values=arrayOfNulls<Any>(constructor.parameterCount)
                constructor.parameterTypes.forEachIndexed { i,type -> if(type==Int::class.javaPrimitiveType) values[i]=0 }
                values[0]=rows;values[1]=clockProxy;values[constructor.parameterCount-2]=mask
                val restoreStart=SystemClock.elapsedRealtimeNanos();val restoreCpu=Debug.threadCpuTimeNanos()
                val memory=constructor.newInstance(*values)
                val restoreCpuNs=Debug.threadCpuTimeNanos()-restoreCpu;val restoreWallNs=SystemClock.elapsedRealtimeNanos()-restoreStart
                assertSame(loader,memory.javaClass.classLoader)
                val restored=recordsField.get(memory) as Map<*,*>
                val aliases=aliasField.get(memory) as Map<*,*>
                assertEquals(size,restored.size)
                val aliasCount=(aliases["qqq"] as? Collection<*>)?.size ?: 0
                assertEquals(if(step.optBoolean("sharedAlias",true)) 128 else 0,aliasCount)
                output.appendText(JSONObject().put("type","row").put("index",index).put("size",size)
                    .put("profiled",profile).put("sharedAlias",step.optBoolean("sharedAlias",true))
                    .put("readWallNs",readWallNs).put("readCpuNs",readCpuNs)
                    .put("restoreWallNs",restoreWallNs).put("restoreCpuNs",restoreCpuNs)
                    .put("restoredRecords",restored.size).put("qqqAliasCount",aliasCount)
                    .put("warmup",step.optBoolean("warmup",false))
                    .put("stateSha256",if(step.optBoolean("fingerprint",false)) fingerprint(memoryClass,memory) else "")
                    .put("remoteProfile",if(profile)remote else "").toString()+"\n")
            } finally {
                if(profile)File(directory,"$index-profile-stop.txt").writeText(device.executeShellCommand("am profile stop ${android.os.Process.myPid()}"))
                close.invoke(handle)
            }
        }
        context.deleteDatabase("sense_user_lexicon.db")
        output.appendText(JSONObject().put("type","summary").put("steps",steps.length()).put("passed",true).toString()+"\n")
    }

    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}

    /** Outside timed sections. Include all learned fields and set iteration order for future tie eviction. */
    private fun fingerprint(type:Class<*>,memory:Any):String {
        val result=StringBuilder()
        for(name in listOf("records","fullIndex","initialsIndex","aliasIndex")) {
            val entries=type.getDeclaredField(name).apply { isAccessible=true }.get(memory) as Map<*,*>
            result.append(name).append('\n')
            entries.entries.sortedBy { it.key.toString() }.forEach {
                result.append(it.key).append('\t').append(it.value).append('\n')
            }
        }
        val lengths=type.getDeclaredField("fullCodeLengths").apply { isAccessible=true }.get(memory) as Array<*>
        lengths.forEach { result.append((it as IntArray).joinToString(",")).append('\n') }
        result.append(type.getDeclaredField("latestAssignedUsedAtMillis").apply { isAccessible=true }.get(memory))
        return sha(result.toString().toByteArray(Charsets.UTF_8))
    }
}
