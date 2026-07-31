using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace Sense.AgentHost;

internal static class Program
{
    private const string Protocol = "sense.agent.bridge.v1";
    private const string PipeName = "sense.agent.v1";
    private const int MaximumMessageChars = 262_144;

    public static async Task<int> Main(string[] args)
    {
        if (args.Contains("--probe", StringComparer.OrdinalIgnoreCase))
        {
            Console.WriteLine(BridgeResponse.Ready());
            return 0;
        }

        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            return SelfTest();
        }

        using var singleInstance = new Mutex(true, @"Local\Sense.AgentHost.v1", out var ownsMutex);
        if (!ownsMutex)
        {
            return 0;
        }

        using var cancellation = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            cancellation.Cancel();
        };

        var server = new AgentBridgeServer();
        await server.RunAsync(cancellation.Token).ConfigureAwait(false);
        return 0;
    }

    private static int SelfTest()
    {
        using var document = JsonDocument.Parse(BridgeResponse.Ready());
        var root = document.RootElement;
        var valid = root.GetProperty("protocol").GetString() == Protocol
            && root.GetProperty("type").GetString() == "bridge.ready"
            && root.GetProperty("capabilities").GetArrayLength() >= 3;
        Console.WriteLine(valid ? "Sense.AgentHost self-test passed" : "Sense.AgentHost self-test failed");
        return valid ? 0 : 1;
    }

    private sealed class AgentBridgeServer
    {
        public async Task RunAsync(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await using var pipe = new NamedPipeServerStream(
                    PipeName,
                    PipeDirection.InOut,
                    4,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                try
                {
                    await pipe.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                    await ServeConnectionAsync(pipe, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    break;
                }
                catch (IOException)
                {
                    // A new pipe instance accepts the next client.
                }
            }
        }

        private static async Task ServeConnectionAsync(
            NamedPipeServerStream pipe,
            CancellationToken cancellationToken)
        {
            using var reader = new StreamReader(
                pipe,
                new UTF8Encoding(false, true),
                detectEncodingFromByteOrderMarks: false,
                bufferSize: 4096,
                leaveOpen: true);
            await using var writer = new StreamWriter(
                pipe,
                new UTF8Encoding(false),
                bufferSize: 4096,
                leaveOpen: true)
            {
                AutoFlush = true,
                NewLine = "\n",
            };

            while (pipe.IsConnected && !cancellationToken.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                if (line is null)
                {
                    return;
                }
                if (line.Length > MaximumMessageChars)
                {
                    await writer.WriteLineAsync(
                        BridgeResponse.Error("MESSAGE_TOO_LARGE", requestId: null)).ConfigureAwait(false);
                    continue;
                }

                foreach (var response in Route(line))
                {
                    await writer.WriteLineAsync(response).ConfigureAwait(false);
                }
            }
        }

        private static IEnumerable<string> Route(string line)
        {
            JsonDocument? document = null;
            try
            {
                document = JsonDocument.Parse(line, new JsonDocumentOptions
                {
                    MaxDepth = 24,
                    AllowTrailingCommas = false,
                    CommentHandling = JsonCommentHandling.Disallow,
                });
            }
            catch (JsonException)
            {
                // Iterator blocks cannot yield from a catch body.
            }
            if (document is null)
            {
                yield return BridgeResponse.Error("MALFORMED_JSON", requestId: null);
                yield break;
            }

            using (document)
            {
                var root = document.RootElement;
                var protocol = ReadString(root, "protocol");
                var type = ReadString(root, "type");
                var requestId = ReadNestedString(root, "snapshot", "request_id")
                    ?? ReadString(root, "request_id");

                if (protocol != Protocol)
                {
                    yield return BridgeResponse.Error("PROTOCOL_MISMATCH", requestId);
                    yield break;
                }
                if (type == "handshake")
                {
                    yield return BridgeResponse.Ready();
                    yield break;
                }
                if (type != "skill.invoke")
                {
                    yield return BridgeResponse.Error("UNKNOWN_MESSAGE_TYPE", requestId);
                    yield break;
                }

                var configuration = AgentConfiguration.Load();
                if (!configuration.AgentEnabled)
                {
                    yield return BridgeResponse.State("disabled", requestId);
                    yield break;
                }
                if (string.IsNullOrWhiteSpace(configuration.ProviderEndpoint)
                    || string.IsNullOrWhiteSpace(configuration.ProviderModel))
                {
                    yield return BridgeResponse.State("provider_unconfigured", requestId);
                    yield break;
                }

                var runId = Guid.NewGuid().ToString("N");
                yield return BridgeResponse.Accepted(runId, requestId);
                yield return BridgeResponse.Complete(runId, requestId, "bridge_reserved");
            }
        }

        private static string? ReadString(JsonElement element, string propertyName) =>
            element.TryGetProperty(propertyName, out var property)
            && property.ValueKind == JsonValueKind.String
                ? property.GetString()
                : null;

        private static string? ReadNestedString(
            JsonElement element,
            string parent,
            string child) =>
            element.TryGetProperty(parent, out var nested)
            && nested.ValueKind == JsonValueKind.Object
                ? ReadString(nested, child)
                : null;
    }

    private sealed record AgentConfiguration(
        bool AgentEnabled,
        string ProviderEndpoint,
        string ProviderModel)
    {
        public static AgentConfiguration Load()
        {
            var path = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Sense",
                "settings.json");
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllText(path, Encoding.UTF8));
                var root = document.RootElement;
                return new AgentConfiguration(
                    root.TryGetProperty("agentEnabled", out var enabled)
                        && enabled.ValueKind is JsonValueKind.True,
                    Read(root, "providerEndpoint"),
                    Read(root, "providerModel"));
            }
            catch (IOException)
            {
                return new AgentConfiguration(false, string.Empty, string.Empty);
            }
            catch (JsonException)
            {
                return new AgentConfiguration(false, string.Empty, string.Empty);
            }
        }

        private static string Read(JsonElement root, string name) =>
            root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
                ? value.GetString() ?? string.Empty
                : string.Empty;
    }

    private static class BridgeResponse
    {
        private static readonly JsonSerializerOptions Options = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        };

        public static string Ready() => JsonSerializer.Serialize(new
        {
            protocol = Protocol,
            type = "bridge.ready",
            version = 1,
            capabilities = new[]
            {
                "skill.invoke",
                "stream.events",
                "editor.patch.v1",
                "credential.store.v1",
            },
            transport = "named_pipe_ndjson",
        }, Options);

        public static string Error(string code, string? requestId) =>
            JsonSerializer.Serialize(new
            {
                protocol = Protocol,
                type = "bridge.error",
                requestId,
                code,
            }, Options);

        public static string State(string status, string? requestId) =>
            JsonSerializer.Serialize(new
            {
                protocol = Protocol,
                type = "bridge.state",
                requestId,
                status,
            }, Options);

        public static string Accepted(string runId, string? requestId) =>
            JsonSerializer.Serialize(new
            {
                protocol = Protocol,
                type = "run.accepted",
                requestId,
                runId,
            }, Options);

        public static string Complete(string runId, string? requestId, string status) =>
            JsonSerializer.Serialize(new
            {
                protocol = Protocol,
                type = "run.completed",
                requestId,
                runId,
                status,
            }, Options);
    }
}
