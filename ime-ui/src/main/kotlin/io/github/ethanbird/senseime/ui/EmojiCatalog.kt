package io.github.ethanbird.senseime.ui

/** Stable identifiers used by the Emoji panel's category rail. */
enum class EmojiCategoryId {
    SMILEYS,
    PEOPLE,
    ANIMALS_NATURE,
    FOOD_DRINK,
    ACTIVITIES,
    TRAVEL_PLACES,
    OBJECTS,
    SYMBOLS_FLAGS,
    GESTURES_BODY,
    HEARTS,
    FLAGS,
}

/**
 * One flat, immutable Emoji sequence.
 *
 * Keeping the values flat lets the keyboard virtualize rows and apply a
 * continuous pixel scroll without introducing page boundaries into the data.
 */
data class EmojiCategory(
    val id: EmojiCategoryId,
    val label: String,
    val icon: String,
    val values: List<String>,
)

/**
 * Offline Emoji catalog for the keyboard panel.
 *
 * The catalog intentionally avoids network or platform Emoji APIs so its order
 * stays deterministic across Android versions. Unsupported glyphs remain safe
 * strings and are rendered by the device font when available.
 */
object EmojiCatalog {
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            EmojiCategoryId.SMILEYS,
            "表情",
            "😀",
            emojiValues(
                """
                😀 😃 😄 😁 😆 😅 😂 🤣 🥲 🥹 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚
                😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 🙂‍↕️ 😏 😒 🙂‍↔️ 😞 😔 😟 😕 🙁 ☹️
                😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😶‍🌫️ 😱 😨 😰 😥 😓
                🤗 🤔 🫣 🤭 🫢 🫡 🤫 🫠 🤥 😶 🫥 😐 🫤 😑 😬 🙄 😯 😦 😧 😮 😲
                🥱 😴 🤤 😪 😮‍💨 😵 😵‍💫 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹
                👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.PEOPLE,
            "人物",
            "👋",
            emojiValues(
                """
                👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 🫷 🫸 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙
                👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏
                ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄 🫦
                👶 🧒 👦 👧 🧑 👱 👨 🧔 🧔‍♂️ 🧔‍♀️ 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋
                🧏 🙇 🤦 🤷 🧑‍⚕️ 🧑‍🎓 🧑‍🏫 🧑‍⚖️ 🧑‍🌾 🧑‍🍳 🧑‍🔧 🧑‍🏭 🧑‍💼 🧑‍🔬
                🧑‍💻 🧑‍🎤 🧑‍🎨 🧑‍✈️ 🧑‍🚀 🧑‍🚒 👮 🕵️ 💂 🥷 👷 🫅 🤴 👸 👳
                👲 🧕 🤵 👰 🤰 🫃 🫄 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟
                💆 💇 🚶 🧍 🧎 🧑‍🦯 🧑‍🦼 🧑‍🦽 🏃 💃 🕺 🕴️ 👯 🧖 🧗 🤺 🏇 ⛷️
                🏂 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 👫 👭 👬
                💏 💑 👪 🗣️ 👤 👥 🫂 👣
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.ANIMALS_NATURE,
            "自然",
            "🐼",
            emojiValues(
                """
                🐵 🐒 🦍 🦧 🐶 🐕 🦮 🐕‍🦺 🐩 🐺 🦊 🦝 🐱 🐈 🐈‍⬛ 🦁 🐯 🐅 🐆
                🐴 🫎 🫏 🐎 🦄 🦓 🦌 🦬 🐮 🐂 🐃 🐄 🐷 🐖 🐗 🐽 🐏 🐑 🐐 🐪
                🐫 🦙 🦒 🐘 🦣 🦏 🦛 🐭 🐁 🐀 🐹 🐰 🐇 🐿️ 🦫 🦔 🦇 🐻 🐻‍❄️
                🐨 🐼 🦥 🦦 🦨 🦘 🦡 🐾 🦃 🐔 🐓 🐣 🐤 🐥 🐦 🐧 🕊️ 🦅 🦆 🦢
                🦉 🦤 🪶 🦩 🦚 🦜 🪽 🐦‍⬛ 🪿 🐦‍🔥 🐸 🐊 🐢 🦎 🐍 🐲 🐉 🦕 🦖
                🐳 🐋 🐬 🦭 🐟 🐠 🐡 🦈 🐙 🐚 🪸 🪼 🐌 🦋 🐛 🐜 🐝 🪲 🐞 🦗
                🪳 🕷️ 🕸️ 🦂 🦟 🪰 🪱 🦠 💐 🌸 💮 🪷 🌹 🥀 🌺 🌻 🌼 🌷 🪻 🌱
                🪴 🌲 🌳 🌴 🌵 🌾 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🪨 🪵 🌰 🐚 🌍 🌎 🌏
                🌐 🗺️ 🗾 🧭 🏔️ ⛰️ 🌋 🗻 🏕️ 🏖️ 🏜️ 🏝️ 🏞️ 🌅 🌄 🌠 🎇 🎆
                🌇 🌆 🏙️ 🌃 🌌 🌉 🌁 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️
                ⛄ 🌬️ 💨 💧 💦 ☔ ☂️ 🌊 🌫️ 🌈 🔥 ⚡ ⭐ 🌟 ✨ 💫 ☄️ 🌙 🌚 🌛 🌜
                🌝 🌞 🪐
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.FOOD_DRINK,
            "食物",
            "🍜",
            emojiValues(
                """
                🍏 🍎 🍐 🍊 🍋 🍋‍🟩 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅
                🍆 🥑 🫛 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🫚 🫜 🥐 🥯 🍞
                🥖 🫓 🥨 🥞 🧇 🧀 🍖 🍗 🥩 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🫔 🥙 🧆
                🥚 🍳 🥘 🍲 🫕 🥣 🥗 🍿 🧈 🧂 🥫 🍱 🍘 🍙 🍚 🍛 🍜 🍝 🍢 🍣
                🍤 🍥 🥮 🍡 🥟 🥠 🥡 🦀 🦞 🦐 🦑 🦪 🍦 🍧 🍨 🍩 🍪 🎂 🍰 🧁
                🥧 🍫 🍬 🍭 🍮 🍯 🍼 🥛 ☕ 🫖 🍵 🍶 🍾 🍷 🍸 🍹 🍺 🍻 🥂 🥃
                🫗 🥤 🧋 🧃 🧉 🧊 🥢 🍽️ 🍴 🥄 🔪 🫙
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.ACTIVITIES,
            "活动",
            "⚽",
            emojiValues(
                """
                ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳
                🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 🏋️ 🤼 🤸 ⛹️
                🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️
                🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕
                🎻 🪈 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.TRAVEL_PLACES,
            "出行",
            "🚗",
            emojiValues(
                """
                🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵
                🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇
                🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓
                🛟 ⛽ 🚧 🚦 🚥 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️
                🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥
                🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🛕 🕍 ⛩️ 🕋 🛤️ 🛣️ 🗺️ 🧭
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.OBJECTS,
            "物品",
            "💡",
            emojiValues(
                """
                ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹
                🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳
                📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️
                🪜 🧰 🪛 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ ⛓️‍💥 🧲 🔫 💣 🧨
                🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈 ⚗️ 🔭 🔬 🕳️
                🩹 🩺 🩻 🩼 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚿 🛁
                🪥 🪒 🧴 🧷 🧼 🫧 🧽 🧯 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🎎 🏮 🎐
                🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄
                📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️
                📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍
                ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.SYMBOLS_FLAGS,
            "符号",
            "❤️",
            emojiValues(
                """
                ❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗
                💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌
                ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚
                💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛
                🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️
                ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿
                🅿️ 🛗 🈳 🈂️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼 ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️
                🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣
                🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ 🔀 🔁 🔂 ◀️ 🔼
                🔽 ⏫ ⏬ ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ 🔄 ↪️ ↩️ ⤴️ ⤵️
                🔃 🔚 🔙 🔛 🔝 🔜 ☑️ ✔️ 〰️ ➰ ➿ ✖️ ➕ ➖ ➗ 🟰 ©️ ®️ ™️
                🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇨🇳 🇭🇰 🇲🇴 🇹🇼 🇯🇵 🇰🇷 🇸🇬
                🇲🇾 🇹🇭 🇻🇳 🇮🇳 🇬🇧 🇫🇷 🇩🇪 🇮🇹 🇪🇸 🇷🇺 🇺🇦 🇺🇸 🇨🇦 🇲🇽 🇧🇷
                🇦🇷 🇦🇺 🇳🇿 🇿🇦 🇪🇬 🇪🇺 🇺🇳
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.GESTURES_BODY,
            "手势",
            "👍",
            gestureAndBodyValues(),
        ),
        EmojiCategory(
            EmojiCategoryId.HEARTS,
            "爱心",
            "❤️",
            emojiValues(
                """
                ❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗
                💖 💘 💝 💟 ♥️ 🥰 😍 😘 😗 😙 😚 🥹 😊 🤗 🫶 🫰 💋 💌 💐 🌹
                🥀 🌺 🌷 🌸 💮 🪷 🪻 🌻 🌼 🎀 🎁 💍 💎 🕯️ 🎂 🍫 🍬 🍭 🧸 🦢
                🕊️ 🦋 🌙 ⭐ 🌟 ✨ 💫 🥂 🍷 🎶 🎵 🏩 💒 👩‍❤️‍👨 👩‍❤️‍👩 👨‍❤️‍👨 💑
                👩‍❤️‍💋‍👨 👩‍❤️‍💋‍👩 👨‍❤️‍💋‍👨 🫂 🤝 🫶🏻 🫶🏼
                🫶🏽 🫶🏾 🫶🏿 🫰🏻 🫰🏼 🫰🏽 🫰🏾 🫰🏿
                """,
            ),
        ),
        EmojiCategory(
            EmojiCategoryId.FLAGS,
            "旗帜",
            "🏳️",
            flagValues(
                """
                AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ
                BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ
                CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ
                DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR
                GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY
                HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT
                JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ
                LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM
                MN MO MP MQ MR MS MT MU MV MW MX MY MZ
                NA NC NE NF NG NI NL NO NP NR NU NZ OM
                PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA
                RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST
                SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ
                UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW
                EU UN
                """,
            ),
        ),
    )

    private val byId = categories.associateBy(EmojiCategory::id)

    init {
        require(byId.size == EmojiCategoryId.entries.size)
        require(categories.all { it.values.isNotEmpty() })
    }

    val totalCount: Int
        get() = categories.sumOf { it.values.size }

    fun category(id: EmojiCategoryId): EmojiCategory = requireNotNull(byId[id])
}

private fun emojiValues(raw: String): List<String> =
    raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

private fun gestureAndBodyValues(): List<String> {
    val fixed = emojiValues(
        """
        👐 🤲 🤝 🙏 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄
        🫦 👶 🧒 👦 👧 🧑 👱 👨 👩 🧔 🧓 👴 👵 🗣️ 👤 👥 🫂 👣
        """,
    )
    val toneable = emojiValues(
        """
        👋 🤚 🖐 ✋ 🖖 🫱 🫲 🫳 🫴 🫷 🫸 👌 🤌 🤏 ✌ 🤞 🫰 🤟 🤘 🤙
        👈 👉 👆 🖕 👇 ☝ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 ✍ 💅 🤳
        """,
    )
    return buildList(fixed.size + toneable.size * (SKIN_TONES.size + 1)) {
        addAll(fixed)
        toneable.forEach { base ->
            add(base)
            SKIN_TONES.forEach { tone -> add(base + tone) }
        }
    }
}

private fun flagValues(rawCountryCodes: String): List<String> = buildList {
    addAll(emojiValues("🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️"))
    emojiValues(rawCountryCodes).forEach { countryCode ->
        require(countryCode.length == 2 && countryCode.all { it in 'A'..'Z' })
        val first = REGIONAL_INDICATOR_A + (countryCode[0] - 'A')
        val second = REGIONAL_INDICATOR_A + (countryCode[1] - 'A')
        add(
            StringBuilder(4)
                .appendCodePoint(first)
                .appendCodePoint(second)
                .toString(),
        )
    }
}

private val SKIN_TONES = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
private const val REGIONAL_INDICATOR_A = 0x1F1E6
