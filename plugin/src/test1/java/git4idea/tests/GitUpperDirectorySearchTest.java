/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.tests;

import consulo.ide.impl.idea.openapi.util.io.FileUtil;
import consulo.ui.ex.awt.UIUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import consulo.ide.impl.idea.vcsUtil.VcsFileUtil;
import consulo.application.util.SystemInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testng.Assert;

import java.io.File;

/**
 * @author irengrig
 */
public class GitUpperDirectorySearchTest {
  private LocalFileSystem myLocalFileSystem;
  private IdeaProjectTestFixture myProjectFixture;

  @Before
  public void setUp() throws Exception {
    final TestFixtureBuilder<IdeaProjectTestFixture> testFixtureBuilder = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder();
    myProjectFixture = testFixtureBuilder.getFixture();
    myProjectFixture.setUp();

    myLocalFileSystem = LocalFileSystem.getInstance();
  }

  @After
  public void tearDown() throws Exception {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        try {
          myProjectFixture.tearDown();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  @Test
  public void testDirFinding() {
    final String dirName = "somedir";
    final File file = new File(myProjectFixture.getProject().getBaseDir().getPath(), dirName);
    FileUtil.delete(file);
    Assert.assertTrue(file.mkdir(), "can't create: " + file.getAbsolutePath());
    final File childDir = new File(file, "and/a/path/to");
    Assert.assertTrue(childDir.mkdirs(), "can't create: " + childDir.getAbsolutePath());

    final VirtualFile dir = myLocalFileSystem.refreshAndFindFileByIoFile(file);
    final VirtualFile childFile = myLocalFileSystem.refreshAndFindFileByIoFile(childDir);

    Assert.assertNotNull(dir);
    Assert.assertNotNull(childFile);

    final VirtualFile result = consulo.ide.impl.idea.vcsUtil.VcsFileUtil.getPossibleBase(childFile, dirName);
    Assert.assertEquals(result, dir);
  }

  @Test
  public void testLongPattern() throws Exception {
    final String dirName = SystemInfo.isFileSystemCaseSensitive ? "somedir/long/path" : "somEdir/lonG/path";
    final File file = new File(myProjectFixture.getProject().getBaseDir().getPath(), "somedir");
    FileUtil.delete(file);
    Assert.assertTrue(file.mkdir(), "can't create: " + file.getAbsolutePath());
    final File childDir = new File(file, "long/path/and/a/path/to");
    Assert.assertTrue(childDir.mkdirs(), "can't create: " + childDir.getAbsolutePath());
    final File a = new File(childDir, "a.txt");
    Assert.assertTrue(a.createNewFile(), "can't create: " + a.getAbsolutePath());

    final VirtualFile dir = myLocalFileSystem.refreshAndFindFileByIoFile(file);
    final VirtualFile childFile = myLocalFileSystem.refreshAndFindFileByIoFile(a);

    Assert.assertNotNull(dir);
    Assert.assertNotNull(childFile);

    final VirtualFile result = consulo.ide.impl.idea.vcsUtil.VcsFileUtil.getPossibleBase(childFile, dirName.split("/"));
    Assert.assertEquals(result, dir);
  }

  @Test
  public void testLongRepeatedPattern() throws Exception {
    final String dirName = SystemInfo.isFileSystemCaseSensitive ? "somedir/long/path" : "somEdir/lonG/path";
    final File file = new File(myProjectFixture.getProject().getBaseDir().getPath(), "somedir");
    FileUtil.delete(file);
    Assert.assertTrue(file.mkdir(), "can't create: " + file.getAbsolutePath());
    final File childDir = new File(file, "long/path/and/a/path/path/path/to/long/path");
    Assert.assertTrue(childDir.mkdirs(), "can't create: " + childDir.getAbsolutePath());
    final File a = new File(childDir, "a.txt");
    Assert.assertTrue(a.createNewFile(), "can't create: " + a.getAbsolutePath());

    final VirtualFile dir = myLocalFileSystem.refreshAndFindFileByIoFile(file);
    final VirtualFile childFile = myLocalFileSystem.refreshAndFindFileByIoFile(a);

    Assert.assertNotNull(dir);
    Assert.assertNotNull(childFile);

    final VirtualFile result = consulo.ide.impl.idea.vcsUtil.VcsFileUtil.getPossibleBase(childFile, dirName.split("/"));
    Assert.assertEquals(result, dir);
  }
}
