package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.process.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixWorkspaceTest {

    @TempDir
    Path tmp;

    @Test
    void replaces_parsesLocalPathsInBothForms_andIgnoresRemoteOnes() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("services"));
        Files.createDirectories(repo.resolve("vendor/x"));
        Files.writeString(repo.resolve("services/go.mod"), """
                module m
                replace github.com/CryptoLabInc/evi/bindings/cgo => ../external/evi/bindings/cgo
                replace (
                    github.com/CryptoLabInc/license-manager/go v0.0.0 => ../external/license-manager/go // comment
                    golang.org/x/net => golang.org/x/net v0.62.0
                )
                """);
        Files.writeString(repo.resolve("vendor/x/go.mod"), "replace a => ../b\n");

        List<FixWorkspace.Replace> r = FixWorkspace.replaces(repo);

        assertThat(r).extracting(FixWorkspace.Replace::module)
                .containsExactly("github.com/CryptoLabInc/evi/bindings/cgo", "github.com/CryptoLabInc/license-manager/go");
        assertThat(r.get(0).target()).isEqualTo(repo.resolve("external/evi/bindings/cgo"));
    }

    @Test
    void submodules_parsesHttpsAndSshUrls() throws Exception {
        Path repo = tmp.resolve("r2");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve(".gitmodules"), """
                [submodule "external/HE-KMS"]
                	path = external/HE-KMS
                	url = https://github.com/CryptoLabInc/HE-KMS.git
                [submodule "external/enword"]
                	path = external/enword
                	url = git@github.com:CryptoLabInc/enword.git
                """);

        assertThat(FixWorkspace.submodules(repo)).containsEntry("external/HE-KMS", "HE-KMS")
                .containsEntry("external/enword", "enword");
    }

    @Test
    void prepare_fetchesReachableSubmodule_stubsPrivateOne_andKeepsTokenOutOfArgs() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        List<List<String>> commands = new ArrayList<>();
        List<Map<String, String>> envs = new ArrayList<>();
        Path work = tmp.resolve("work");
        when(runner.run(any(), any(), any(Duration.class), any(), anyMap())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            commands.add(cmd);
            envs.add(inv.getArgument(4));
            String joined = String.join(" ", cmd);
            Path repo = work.resolve("job1/repo");
            if (joined.contains(" clone ")) {
                Files.createDirectories(repo.resolve("agents"));
                Files.writeString(repo.resolve("agents/go.mod"), """
                        module a
                        replace github.com/CryptoLabInc/enword/bindings/cgo => ../external/enword/bindings/cgo
                        replace github.com/CryptoLabInc/HE-KMS/sdk/go => ../external/HE-KMS/sdk/go
                        """);
                Files.writeString(repo.resolve(".gitmodules"), """
                        path = external/enword
                        url = git@github.com:CryptoLabInc/enword.git
                        path = external/HE-KMS
                        url = git@github.com:CryptoLabInc/HE-KMS.git
                        """);
                return new ProcessRunner.Result(0, "", "", false);
            }
            if (joined.contains("ls-tree")) {
                return new ProcessRunner.Result(0, "160000 commit " + "a".repeat(40) + "\t" + cmd.get(cmd.size() - 1), "", false);
            }
            if (joined.contains("fetch") && joined.contains("enword")) {
                return new ProcessRunner.Result(128, "", "Repository not found", false);
            }
            if (joined.contains("checkout") && joined.contains("HE-KMS")) {
                Files.createDirectories(repo.resolve("external/HE-KMS/sdk/go"));
                Files.writeString(repo.resolve("external/HE-KMS/sdk/go/go.mod"), "module github.com/CryptoLabInc/HE-KMS/sdk/go\n");
            }
            return new ProcessRunner.Result(0, "", "", false);
        });
        CiAlertProperties props = new CiAlertProperties(true, "C", List.of("B"), "CryptoLabInc", List.of("heracles"),
                "SECRET-TOKEN", null, 24, 180, 60, List.of(), null);
        CiFixProperties fixProps = new CiFixProperties(true, "t", null, 0, 0, 0, null, null, null, work.toString(),
                null, null, 0);

        FixWorkspace.Workspace ws = new FixWorkspace(runner, props, fixProps).prepare("heracles", "job1");

        assertThat(ws.fetched()).containsExactly("external/HE-KMS");
        assertThat(ws.stubbed()).containsExactly("github.com/CryptoLabInc/enword/bindings/cgo");
        assertThat(Files.readString(ws.repoDir().resolve("external/enword/bindings/cgo/go.mod")))
                .startsWith("module github.com/CryptoLabInc/enword/bindings/cgo");
        assertThat(commands).allSatisfy(c -> assertThat(String.join(" ", c)).doesNotContain("SECRET-TOKEN"));
        String header = "AUTHORIZATION: basic " + Base64.getEncoder().encodeToString("x-access-token:SECRET-TOKEN".getBytes());
        assertThat(envs).anySatisfy(e -> assertThat(e).containsEntry("GIT_CONFIG_VALUE_0", header));
        assertThat(commands).anySatisfy(c -> assertThat(c).containsSequence("rsync", "-a", "--exclude=.git"));
    }

    @Test
    void prepare_withRef_checksOutThatCommitAfterCloningTheBranch_andRejectsShortShas() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        List<List<String>> commands = new ArrayList<>();
        Path work = tmp.resolve("work2");
        when(runner.run(any(), any(), any(Duration.class), any(), anyMap())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            commands.add(cmd);
            if (String.join(" ", cmd).contains(" clone ")) {
                Files.createDirectories(work.resolve("job2/repo"));
            }
            return new ProcessRunner.Result(0, "", "", false);
        });
        CiAlertProperties props = new CiAlertProperties(true, "C", List.of("B"), "CryptoLabInc", List.of("envector"),
                "T", null, 24, 180, 60, List.of(), null);
        CiFixProperties fixProps = new CiFixProperties(true, "t", null, 0, 0, 0, null, null, null, work.toString(),
                null, null, 0);
        FixWorkspace fw = new FixWorkspace(runner, props, fixProps);
        String sha = "ab2e546d" + "0".repeat(32);

        fw.prepare("envector", "job2", "main", sha);

        List<String> joined = commands.stream().map(c -> String.join(" ", c)).toList();
        int clone = joined.indexOf(joined.stream().filter(c -> c.contains(" clone ")).findFirst().orElseThrow());
        assertThat(joined.get(clone)).contains("--branch main");
        assertThat(joined.get(clone + 1)).isEqualTo("git fetch -q --depth 1 origin " + sha);
        assertThat(joined.get(clone + 2)).isEqualTo("git checkout -q --detach FETCH_HEAD");
        assertThatThrownBy(() -> fw.prepare("envector", "job3", "main", "ab2e546d"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40자");
    }
}
