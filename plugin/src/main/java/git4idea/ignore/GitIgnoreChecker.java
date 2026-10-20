// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.project.ProjectLocator;
import consulo.versionControlSystem.Ignored;
import consulo.versionControlSystem.IgnoredCheckResult;
import consulo.versionControlSystem.NotIgnored;
import consulo.versionControlSystem.VcsIgnoreChecker;
import consulo.versionControlSystem.VcsKey;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;

import java.nio.file.Path;
import java.util.List;

@ExtensionImpl
public class GitIgnoreChecker implements VcsIgnoreChecker {
    @Override
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Override
    public IgnoredCheckResult isIgnored(VirtualFile vcsRoot, Path file) {
        return isIgnored(vcsRoot, file.toAbsolutePath().normalize().toString().replace('\\', '/'), false);
    }

    @Override
    public IgnoredCheckResult isFilePatternIgnored(VirtualFile vcsRoot, String filePattern) {
        return isIgnored(vcsRoot, filePattern, true);
    }

    private IgnoredCheckResult isIgnored(VirtualFile vcsRoot, String checkForIgnore, boolean isPattern) {
        Project project = ProjectLocator.getInstance().guessProjectForFile(vcsRoot);
        if (project == null) {
            return NotIgnored.INSTANCE;
        }

        // check-ignore was introduced in 1.8.2,
        // executing the command for older Gits will fail with exit code 1, which we'll treat as "not ignored" for simplicity
        GitLineHandler handler = new GitLineHandler(project, vcsRoot, GitCommand.CHECK_IGNORE);
        handler.addParameters("--verbose");
        handler.endOptions();
        handler.addParameters(checkForIgnore);
        GitCommandResult commandResult = Git.getInstance().runCommand(handler);
        return parseOutput(vcsRoot, checkForIgnore, commandResult.getOutput(), isPattern);
    }

    private static IgnoredCheckResult parseOutput(VirtualFile vcsRoot, String checkForIgnorePath, List<String> output, boolean isPattern) {
        if (output.isEmpty()) {
            return NotIgnored.INSTANCE;
        }

        for (String line : output) {
            //Output form: <source> <COLON> <linenum> <COLON> <pattern> <HT> <pathname>
            String[] lineElements = line.split("\t");
            if (lineElements.length != 2) {
                continue;
            }

            String path = lineElements[1];
            String[] prefixParts = lineElements[0].split(":");
            if (prefixParts.length != 3) {
                continue;
            }

            String gitIgnoreRelPath = prefixParts[0];
            String matchedPattern = prefixParts[2];
            if (matchedPattern.startsWith("!")) {
                // skip matching by negative pattern
                continue;
            }

            VirtualFile gitIgnoreFile = VirtualFileUtil.findRelativeFile(vcsRoot, gitIgnoreRelPath.split("/"));
            if (gitIgnoreFile == null) {
                continue;
            }

            boolean caseSensitive = gitIgnoreFile.getFileSystem().isCaseSensitive();
            if (isPattern && (caseSensitive ? path.equals(checkForIgnorePath) : path.equalsIgnoreCase(checkForIgnorePath))) {
                return new Ignored(VirtualFileUtil.virtualToIoFile(gitIgnoreFile), matchedPattern);
            }
            else if (!isPattern) {
                return new Ignored(VirtualFileUtil.virtualToIoFile(gitIgnoreFile), matchedPattern);
            }
        }
        return NotIgnored.INSTANCE;
    }
}
