package toolcallfaults.runner.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SecretPathScanTest {
    @TempDir Path dir;
    private List<Problem> scan(String content, Set<String> allow) throws Exception {
        Path file = dir.resolve("sample.txt");
        Files.writeString(file, content);
        return SecretPathScan.scan(List.of(file), allow);
    }
    private List<Problem> scan(String content) throws Exception { return scan(content, Set.of()); }
    @Test void flags_key_like_strings() throws Exception {
        assertEquals(4, scan("sk-" + "a".repeat(24) + "\nsk-ant-" + "b".repeat(24)
                + "\nghp_" + "c".repeat(36) + "\nAKIA" + "A".repeat(16)).size());
    }
    @Test void flags_bearer_tokens() throws Exception {
        assertEquals(1, scan("Authorization: Bearer " + "a".repeat(20)).size());
    }
    @Test void flags_home_and_volume_paths() throws Exception {
        assertEquals(4, scan("/Us" + "ers/x/p\n/ho" + "me/x/p\n/Vol" + "umes/D/a\nC:" + "\\Users\\x").size());
    }
    @Test void flags_email_addresses() throws Exception {
        assertEquals(1, scan("someone" + "@" + "example.org").size());
    }
    @Test void ignores_fake_keys_and_cell_keys() throws Exception {
        assertTrue(scan("sk-test apiKey(\"test\") spring-ai@2.0.2 langchain4j@1.23.0 "
                + "spring-ai@2.1.0-RC2 actions/checkout@v4 truncation.name-only@block-stop+set_level").isEmpty());
    }
    @Test void ignores_file_names_with_an_at_sign() throws Exception {
        assertTrue(scan("suite/fixtures/openai-chat/tools.single-call@nonstream.json "
                + "expectations/baseline/spring-ai@2.0.2.json").isEmpty());
    }
    @Test void allow_list_exempts_exact_strings() throws Exception {
        String address = "someone" + "@" + "example.org";
        assertTrue(scan(address, Set.of(address)).isEmpty());
        assertEquals(1, scan(address, Set.of("someone")).size());
    }
    @Test void problem_names_file_and_line() throws Exception {
        var problems = scan("safe\nsafe\n" + "someone" + "@" + "example.org");
        assertEquals(1, problems.size());
        assertEquals(dir.resolve("sample.txt").toString(), problems.get(0).file());
        assertEquals("line 3", problems.get(0).jsonPath());
    }
    @Test void flags_home_paths_without_a_trailing_separator() throws Exception {
        assertEquals(3, scan("\"/Us" + "ers/someone\"\ncd /ho" + "me/someone\nC:" + "\\\\Users\\\\x").size());
    }
    @Test void flags_temporary_directories_of_this_platform() throws Exception {
        assertEquals(3, scan("/priv" + "ate/tmp/x\n/priv" + "ate/var/x\n/var/fol" + "ders/9y/abc").size());
    }
    @Test void flags_further_token_and_key_shapes() throws Exception {
        assertEquals(4, scan("github" + "_pat_" + "a".repeat(30) + "\ngho" + "_" + "b".repeat(36)
                + "\nAS" + "IA" + "A".repeat(16) + "\n-----BEGIN RSA PRIV" + "ATE KEY-----").size());
    }
}
