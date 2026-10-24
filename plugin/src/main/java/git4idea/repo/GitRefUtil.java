// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import consulo.logging.Logger;
import consulo.util.collection.ContainerUtil;
import consulo.util.io.FileUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.base.HashImpl;
import git4idea.validators.GitRefNameValidator;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static git4idea.GitBranch.REFS_HEADS_PREFIX;
import static git4idea.GitBranch.REFS_REMOTES_PREFIX;

public final class GitRefUtil {
    private static final Logger LOG = Logger.getInstance(GitRefUtil.class);
    private static final Pattern BRANCH_PATTERN = Pattern.compile(" *(?:ref:)? */?((?:refs/heads/|refs/remotes/)?\\S+)\\s*");

    private GitRefUtil() {
    }

    public static @Nullable String addRefsHeadsPrefixIfNeeded(@Nullable String branchName) {
        if (branchName != null && !branchName.startsWith(REFS_HEADS_PREFIX)) {
            return REFS_HEADS_PREFIX + branchName;
        }
        return branchName;
    }

    /**
     * @return pairs [branchName, hash], only for local and remote branches
     * @see #parseRefsLine
     */
    public static @Nullable Pair<String, String> parseBranchesLine(String line) {
        Pair<String, String> parsedRef = parseRefsLine(line);
        if (parsedRef == null) {
            return null;
        }
        String branch = parsedRef.first;
        if (!branch.startsWith(REFS_HEADS_PREFIX) && !branch.startsWith(REFS_REMOTES_PREFIX)) {
            return null;
        }
        return parsedRef;
    }

    /**
     * Parses a line with a pair of hash and ref name e.g. see the .git/packed-refs file.
     * Comments and tags are ignored, and null is returned.
     * Incorrectly formatted lines are ignored, a warning is printed to the log, null is returned.
     * A line indicating a hash which an annotated tag (specified in the previous line) points to, is ignored: null is returned.
     */
    public static @Nullable Pair<String, String> parseRefsLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        char firstChar = trimmed.charAt(0);
        if (firstChar == '#') { // ignoring comments
            return null;
        }
        if (firstChar == '^') {
            // ignoring the hash which an annotated tag above points to
            return null;
        }
        String hash = null;
        int i;
        for (i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                hash = trimmed.substring(0, i);
                break;
            }
        }
        if (hash == null) {
            LOG.warn("Ignoring invalid packed-refs line: [" + trimmed + "]");
            return null;
        }

        String branch = null;
        int start = i;
        if (Character.isWhitespace(trimmed.charAt(start++))) {
            for (i = start; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (Character.isWhitespace(c)) {
                    break;
                }
            }
            branch = trimmed.substring(start, i);
        }

        if (branch == null) {
            return null;
        }
        return Pair.create(branch, hash.trim());
    }

    public static Map<String, String> readFromRefsFiles(File refsRootDir, String prefix, GitRepositoryFiles repositoryFiles) {
        Map<String, String> result = new HashMap<>();
        BiConsumer<String, String> collectingConsumer = result::put;
        readFromRefsFiles(refsRootDir, prefix, repositoryFiles, collectingConsumer);
        return result;
    }

    public static void readFromRefsFiles(File refsRootDir,
                                         String prefix,
                                         GitRepositoryFiles repositoryFiles,
                                         BiConsumer<String, String> consumer) {
        if (!refsRootDir.exists()) {
            return;
        }
        SimpleReference<Boolean> couldNotLoadFile = SimpleReference.create(false);
        FileUtil.processFilesRecursively(refsRootDir, file -> {
            if (!file.isDirectory() && !isHidden(file)) {
                String relativePath = FileUtil.getRelativePath(refsRootDir, file);
                if (relativePath != null) {
                    String branchName = prefix + FileUtil.toSystemIndependentName(relativePath);
                    boolean isBranchNameValid = GitRefNameValidator.getInstance().checkInput(branchName);
                    if (isBranchNameValid) {
                        String hash = loadHashFromBranchFile(file);
                        if (hash != null) {
                            consumer.accept(branchName, hash);
                        }
                        else {
                            couldNotLoadFile.set(true);
                        }
                    }
                }
            }
            return true;
        }, dir -> !isHidden(dir));
        if (couldNotLoadFile.get()) {
            logDebugAllRefsFiles(repositoryFiles);
        }
    }

    private static boolean isHidden(File file) {
        return file.getName().startsWith(".");
    }

    public static @Nullable String loadHashFromBranchFile(File branchFile) {
        return DvcsUtil.tryLoadFileOrReturn(branchFile, null);
    }

    public static void logDebugAllRefsFiles(GitRepositoryFiles gitRepositoryFiles) {
        File refsHeadsFile = gitRepositoryFiles.getRefsHeadsFile();
        File refsRemotesFile = gitRepositoryFiles.getRefsRemotesFile();
        File refsTagsFile = gitRepositoryFiles.getRefsTagsFile();

        LOG.debug("Logging .git/refs files. " +
            ".git/refs/heads " + (refsHeadsFile.exists() ? "exists" : "doesn't exist") +
            ".git/refs/remotes " + (refsRemotesFile.exists() ? "exists" : "doesn't exist") +
            ".git/refs/tags " + (refsTagsFile.exists() ? "exists" : "doesn't exist"));
        if (LOG.isDebugEnabled()) {
            logDebugAllFilesIn(refsHeadsFile);
            logDebugAllFilesIn(refsRemotesFile);
            logDebugAllFilesIn(refsTagsFile);
            File packedRefsPath = gitRepositoryFiles.getPackedRefsPath();
            if (packedRefsPath.exists()) {
                try {
                    LOG.debug("packed-refs file content: [\n" + FileUtil.loadFile(packedRefsPath) + "\n]");
                }
                catch (IOException e) {
                    LOG.debug("Couldn't load the file " + packedRefsPath, e);
                }
            }
            else {
                LOG.debug("The file " + packedRefsPath + " doesn't exist.");
            }
        }
    }

    public static void logDebugAllFilesIn(File dir) {
        List<String> paths = new ArrayList<>();
        FileUtil.processFilesRecursively(dir, file -> {
            if (!file.isDirectory()) {
                paths.add(FileUtil.getRelativePath(dir, file));
            }
            return true;
        });
        LOG.debug("Files in " + dir + ": " + paths);
    }

    static Map<String, Hash> resolveRefs(Map<String, String> data) {
        Map<String, Hash> resolved = getResolvedHashes(data);
        Map<String, String> unresolved = new HashMap<>(ContainerUtil.filter(data, refName -> !resolved.containsKey(refName)));

        boolean progressed = true;
        while (progressed && !unresolved.isEmpty()) {
            progressed = false;
            for (Iterator<Map.Entry<String, String>> iterator = unresolved.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<String, String> entry = iterator.next();
                String refName = entry.getKey();
                String refValue = entry.getValue();
                String link = getTarget(refValue);
                if (link != null) {
                    if (duplicateEntry(resolved, refName, refValue)) {
                        iterator.remove();
                    }
                    else if (!resolved.containsKey(link)) {
                        LOG.debug("Unresolved symbolic link [" + refName + "] pointing to [" + refValue + "]"); // transitive link
                    }
                    else {
                        Hash targetValue = resolved.get(link);
                        resolved.put(refName, targetValue);
                        iterator.remove();
                        progressed = true;
                    }
                }
                else {
                    LOG.warn("Unexpected record [" + refName + "] -> [" + refValue + "]");
                    iterator.remove();
                }
            }
        }
        if (!unresolved.isEmpty()) {
            LOG.warn("Cyclic symbolic links among .git/refs: " + unresolved);
        }
        return resolved;
    }

    public static Map<String, Hash> getResolvedHashes(Map<String, String> data) {
        Map<String, Hash> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String refName = entry.getKey();
            Hash hash = parseHash(entry.getValue());
            if (hash != null && !duplicateEntry(resolved, refName, hash)) {
                resolved.put(refName, hash);
            }
        }
        return resolved;
    }

    static @Nullable String getTarget(String refName) {
        Matcher matcher = BRANCH_PATTERN.matcher(refName);
        if (!matcher.matches()) {
            return null;
        }
        String target = matcher.group(1);
        if (!target.startsWith(REFS_HEADS_PREFIX) && !target.startsWith(REFS_REMOTES_PREFIX)) {
            target = REFS_HEADS_PREFIX + target;
        }
        return target;
    }

    static @Nullable Hash parseHash(String value) {
        try {
            return HashImpl.build(value);
        }
        catch (Exception e) {
            return null;
        }
    }

    static boolean duplicateEntry(Map<String, Hash> resolved, String refName, Object newValue) {
        if (resolved.containsKey(refName)) {
            LOG.error("Duplicate entry for [" + refName + "]. resolved: [" + resolved.get(refName).asString() + "], current: " + newValue + "]");
            return true;
        }
        return false;
    }
}
