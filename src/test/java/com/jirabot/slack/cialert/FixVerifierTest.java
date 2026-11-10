package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GrypeReport.Finding;
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

    // ── alreadyFixed (v0.0.98): 검사할 수 있을 때만 verify 결과를 쓴다 ──

    private static Finding finding(String type, String pkg) {
        return new Finding("GO-1", "High", pkg, "v1", type, List.of("1.26.10"), "fixed", null, Set.of(),
                new java.util.TreeSet<>(List.of("img")), new java.util.TreeSet<>(List.of("amd64")));
    }

    private FixVerifier countingVerifier(int[] calls) {
        CiFixProperties fp = new CiFixProperties(true, "t", null, 0, 0, 0, null, null, null, null, null, null, 0);
        return new FixVerifier(mock(ProcessRunner.class), fp, om) {
            @Override
            public Check verify(FixWorkspace.Workspace ws, List<Finding> targets) {
                calls[0]++;
                return new Check(true, List.of(), List.of("grype ok"));
            }
        };
    }

    private FixWorkspace.Workspace ws() {
        return new FixWorkspace.Workspace("envector", root, root.resolve("repo"), root, List.of(), List.of());
    }

    @Test
    void alreadyFixed_usesVerifyOnlyWhenEveryTargetCanBeChecked() throws Exception {
        int[] calls = {0};
        FixVerifier v = countingVerifier(calls);
        Files.createDirectories(root.resolve("services"));
        Files.writeString(root.resolve("services/go.mod"), "module s\nrequire golang.org/x/net v0.62.0\n");

        assertThat(v.alreadyFixed(ws(), List.of(finding("go-module", "golang.org/x/net"))).ok()).isTrue();
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void alreadyFixed_isNeverOk_whenSomethingCannotBeChecked() throws Exception {
        int[] calls = {0};
        FixVerifier v = countingVerifier(calls);
        Files.createDirectories(root.resolve("services"));
        Files.writeString(root.resolve("services/go.mod"), "module s\nrequire golang.org/x/net v0.62.0\n");

        assertThat(v.alreadyFixed(ws(), List.of(finding("deb", "openssl"))).ok()).as("이미지 빌드가 필요한 종류").isFalse();
        assertThat(v.alreadyFixed(ws(), List.of(finding("go-module", "golang.org/x/net"), finding("go-module", "github.com/a/b"))).ok())
                .as("require 하는 go.mod 가 없는 패키지").isFalse();
        assertThat(v.alreadyFixed(ws(), List.of(finding("stdlib", "stdlib"))).ok()).as("Go 고정값이 없음").isFalse();
        assertThat(v.alreadyFixed(ws(), List.of()).ok()).isFalse();
        assertThat(calls[0]).isZero();

        Files.writeString(root.resolve("Dockerfile"), "ARG GO_VERSION=1.26.10\n");
        assertThat(v.alreadyFixed(ws(), List.of(finding("stdlib", "stdlib"))).ok()).isTrue();
        assertThat(calls[0]).isEqualTo(1);
    }
}
