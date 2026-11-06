package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.process.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixVerifierTest {

    @TempDir
    Path root;
    private final ObjectMapper om = new ObjectMapper();

    // go.dev 호출 대신 고정 값
    private FixVerifier verifier() {
        CiFixProperties fp = new CiFixProperties(true, "t", null, 0, 0, 0, null, null, null, null, null, null, 0);
        return new FixVerifier(mock(ProcessRunner.class), fp, om) {
            @Override
            Map<String, String> goSha256(String version) {
                return "1.26.10".equals(version) ? Map.of("amd64", "a".repeat(64), "arm64", "b".repeat(64)) : Map.of();
            }
        };
    }

    @Test
    void gatingFor_keepsOnlyHighCriticalOfTargetPackages() throws Exception {
        String report = """
                {"matches":[
                 {"vulnerability":{"id":"GHSA-1","severity":"High","fix":{"versions":["1.83.2"]}},"artifact":{"name":"google.golang.org/grpc","version":"v1.83.1"}},
                 {"vulnerability":{"id":"GO-2","severity":"Medium"},"artifact":{"name":"google.golang.org/grpc","version":"v1.83.1"}},
                 {"vulnerability":{"id":"GO-3","severity":"Critical"},"artifact":{"name":"other/pkg","version":"1"}}]}""";

        List<String> left = verifier().gatingFor(om.readTree(report), Set.of("google.golang.org/grpc"));

        assertThat(left).containsExactly("GHSA-1 google.golang.org/grpc v1.83.1 → 1.83.2");
    }

    @Test
    void goModDirsRequiring_findsModulesThatRequireThePackage_skippingSubmodules() throws Exception {
        Files.createDirectories(root.resolve("services"));
        Files.createDirectories(root.resolve("tools"));
        Files.createDirectories(root.resolve("external/HE-KMS/sdk/go"));
        Files.writeString(root.resolve("services/go.mod"), "module s\nrequire (\n\tgolang.org/x/net v0.60.0 // indirect\n)\n");
        Files.writeString(root.resolve("tools/go.mod"), "module t\nrequire golang.org/x/text v0.38.0\n");
        Files.writeString(root.resolve("external/HE-KMS/sdk/go/go.mod"), "module k\nrequire golang.org/x/net v0.60.0\n");

        assertThat(FixVerifier.goModDirsRequiring(root, Set.of("golang.org/x/net"))).containsExactly(root.resolve("services"));
    }

    @Test
    void goPins_mustReachTarget_andMatchOfficialChecksums() throws Exception {
        Files.createDirectories(root.resolve("build/a"));
        Files.writeString(root.resolve("build/a/Dockerfile"), "ARG GO_VERSION=1.26.10\nARG GO_SHA256=" + "a".repeat(64)
                + "\nARG GO_SHA256_ARM64=" + "c".repeat(64) + "\n");
        Files.writeString(root.resolve("Dockerfile.old"), "ARG GO_VER=1.26.8\n");

        List<String> problems = verifier().checkGoPins(root, "1.26.9");

        assertThat(problems).containsExactlyInAnyOrder(
                "build/a/Dockerfile: GO_SHA256_ARM64 가 go.dev 의 go1.26.10 arm64 값과 달라요",
                "Dockerfile.old: Go 1.26.8 < 1.26.9");
    }

    @Test
    void goPins_unknownVersion_isAProblem_andCorrectPinsPass() throws Exception {
        Files.writeString(root.resolve("Dockerfile"), "ARG GO_VERSION=1.26.99\n");
        assertThat(verifier().checkGoPins(root, "1.26.9")).containsExactly("Dockerfile: go.dev 에 Go 1.26.99 배포가 없어요");

        Files.writeString(root.resolve("Dockerfile"), "ARG GO_VERSION=1.26.10\nARG GO_SHA256=" + "a".repeat(64) + "\n");
        assertThat(verifier().checkGoPins(root, "1.26.9")).isEmpty();
    }
}
