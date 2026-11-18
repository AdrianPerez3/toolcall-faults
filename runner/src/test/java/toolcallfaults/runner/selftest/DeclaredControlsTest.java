package toolcallfaults.runner.selftest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.server.RequestRecord;

class DeclaredControlsTest {
  @TempDir Path root;
  ObjectNode manifest;
  String error = "java.lang.IllegalStateException";
  boolean altered;
  int runs;
  Set<String> clients = new HashSet<>();

  @BeforeEach
  void setup() throws Exception {
    Path adapter = root.resolve("adapters/example");
    Files.createDirectories(adapter);
    manifest =
        (ObjectNode)
            FixtureLoader.JSON.readTree(
                """
                {"format":"tcf.adapter/v1","target":"example","modules":{
                  "module":{"wire":"openai-chat","http_client":"jdk"}},
                  "surfaces":["loop-call"],"typed_error_prefixes":["typed."],
                  "control_failures":[{"module":"module","surface":"loop-call",
                  "wire":"openai-chat","tool":"record_note","category":"ERROR_UNTYPED",
                  "error_class":"java.lang.IllegalStateException",
                  "basis":"https://github.com/spring-projects/spring-ai/pull/6775"}]}
                """);
    Files.writeString(
        adapter.resolve("pom.xml"),
        "<project><properties><target.version>1</target.version></properties></project>");
    Path fixture = root.resolve("suite/fixtures/openai-chat/tools.single-call@nonstream.json");
    Files.createDirectories(fixture.getParent());
    Files.copy(Path.of("../suite/fixtures/openai-chat/tools.single-call@nonstream.json"), fixture);
  }

  void runControls() throws Exception {
    Files.writeString(root.resolve("adapters/example/adapter.json"), manifest.toString());
    SelfTest.controls(
        root,
        "example",
        (cell, fixture, rep) -> {
          runs++;
          clients.add(cell.httpClient());
          var line = FixtureLoader.JSON.createObjectNode();
          line.put("format", "tcf.result/v1");
          line.putArray("terminal_signals").add(error == null ? "complete" : "error");
          line.putObject("caller").put("text", "").putArray("finish_reasons");
          if (error != null)
            line.putObject("error").putArray("chain").addObject().put("type", error);
          var invocation = FixtureLoader.JSON.createObjectNode().put("name", "record_note");
          invocation.set("args", fixture.truth().calls().get(0).intendedArguments().deepCopy());
          if (altered) ((ObjectNode) invocation.get("args")).putNull("title");
          return new CellResult(
              cell,
              rep,
              line,
              List.of(invocation),
              false,
              List.of(
                  new RequestRecord(
                      "c",
                      1,
                      "POST",
                      "/v1/chat/completions",
                      0,
                      "test",
                      true,
                      Map.of(),
                      "{}",
                      1,
                      2,
                      true)),
              false,
              false,
              0,
              100, null);
        });
  }

  @Test
  void normal_control_is_checked_on_each_declared_http_client() throws Exception {
    manifest.remove("control_failures");
    error = null;
    ((ObjectNode) manifest.path("modules").path("module"))
        .putArray("http_clients")
        .add("jdk")
        .add("okhttp");
    var scenario = root.resolve("suite/scenarios/tools/single-call.json");
    Files.createDirectories(scenario.getParent());
    Files.writeString(scenario, "{\"http_client_axis\":true}");
    runControls();
    assertEquals(Set.of("jdk", "okhttp"), clients);
    assertEquals(6, runs);
  }

  @Test
  void matching_declared_failure_passes_and_counts_declarations() throws Exception {
    var output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try {
      System.setOut(new PrintStream(output));
      assertDoesNotThrow(this::runControls);
    } finally {
      System.setOut(original);
    }
    assertEquals(3, runs);
    assertTrue(output.toString().contains("controls: 0 of 1 EXECUTED_AS_SENT; 1 declared"));
  }

  @Test
  void undeclared_failing_control_fails() {
    manifest.remove("control_failures");
    assertThrows(IOException.class, this::runControls);
  }

  @Test
  void declared_control_that_now_passes_fails() {
    error = null;
    assertThrows(IOException.class, this::runControls);
  }

  @Test
  void changed_category_fails_even_with_same_error_class() {
    altered = true;
    assertThrows(IOException.class, this::runControls);
  }

  @Test
  void changed_error_class_fails_even_with_same_category() {
    error = "java.lang.IllegalArgumentException";
    assertThrows(IOException.class, this::runControls);
  }

  @Test
  void unexercised_declaration_fails() {
    ((ObjectNode) manifest.path("control_failures").get(0)).put("tool", "set_level");
    error = null;
    assertThrows(IOException.class, this::runControls);
  }

  @Test
  void normal_undeclared_control_passes() {
    manifest.remove("control_failures");
    error = null;
    assertDoesNotThrow(this::runControls);
  }

  @Test
  void declaration_for_a_different_version_does_not_reject_a_passing_control() {
    ((ObjectNode) manifest.path("control_failures").get(0)).putArray("versions").add("older");
    error = null;
    assertDoesNotThrow(this::runControls);
  }
}
