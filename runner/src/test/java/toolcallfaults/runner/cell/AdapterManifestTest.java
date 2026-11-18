package toolcallfaults.runner.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterManifestTest {
  @TempDir Path dir;

  @Test
  void module_scenario_restriction_is_retained_and_enforced() throws Exception {
    Files.writeString(
        dir.resolve("adapter.json"),
        """
        {"format":"tcf.adapter/v1","modules":{
          "limited":{"wire":"openai-chat","http_client":"reactor-netty","scenarios":["tools.single-call"]},
          "full":{"wire":"openai-chat","http_client":"okhttp"}}}
        """);
    var manifest = AdapterManifest.load(dir);
    assertTrue(manifest.modules().get("limited").supportsScenario("tools.single-call"));
    assertFalse(manifest.modules().get("limited").supportsScenario("tools.zero-arg-call"));
    assertTrue(manifest.modules().get("full").supportsScenario("tools.zero-arg-call"));
  }
}
