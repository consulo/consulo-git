/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.crlf;

import consulo.ide.impl.idea.ide.BrowserUtil;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.LinkListener;
import consulo.ui.ex.awt.UIUtil;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBLabel;

import javax.annotation.Nonnull;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;

import static consulo.ui.ex.awt.UIUtil.DEFAULT_HGAP;
import static consulo.ui.ex.awt.UIUtil.DEFAULT_VGAP;
import static git4idea.crlf.GitCrlfUtil.*;

/**
 * Warns the user that CRLF line separators are about to be committed to the repository.
 * Provides some additional information and proposes to set {@code git config --global core.autocrlf true/input}.
 *
 * @author Kirill Likhodedov
 * @see GitCrlfProblemsDetector
 */
public class GitCrlfDialog extends DialogWrapper {

  public static final int SET = DialogWrapper.OK_EXIT_CODE;
  public static final int DONT_SET = DialogWrapper.NEXT_USER_EXIT_CODE;
  public static final int CANCEL = DialogWrapper.CANCEL_EXIT_CODE;
  private JBCheckBox myDontWarn;

  public GitCrlfDialog(@Nullable Project project) {
    super(project, false);

    setOKButtonText("Fix and Commit");
    setCancelButtonText("Cancel");
    setTitle("Line Separators Warning");
    getCancelAction().putValue(DialogWrapper.FOCUSED_ACTION, true);

    init();
  }

  @Nonnull
  @Override
  protected Action[] createActions() {
    return new Action[] { getHelpAction(), getOKAction(), getCancelAction(), new DialogWrapperExitAction("Commit As Is", DONT_SET) };
  }

  @Override
  protected JComponent createCenterPanel() {
    JLabel description = new JBLabel(
      "<html>You are about to commit CRLF line separators to the Git repository.<br/>" +
      "It is recommended to set core.autocrlf Git attribute to <code>" + RECOMMENDED_VALUE +
      "</code> to avoid line separator issues.</html>");

    JLabel additionalDescription = new JBLabel(
      "<html>Fix and Commit: <code>git config --global core.autocrlf " + RECOMMENDED_VALUE + "</code> will be called,<br/>" +
      "Commit as Is: the config value won't be set.</html>", UIUtil.ComponentStyle.SMALL);

    JLabel readMore = new LinkLabel("Read more", null, new LinkListener() {
      @Override
      public void linkSelected(LinkLabel aSource, Object aLinkData) {
        BrowserUtil.browse("https://help.github.com/articles/dealing-with-line-endings");
      }
    });

    JLabel icon = new JBLabel(UIUtil.getWarningIcon(), SwingConstants.LEFT);
    myDontWarn = new JBCheckBox("Don't warn again");
    myDontWarn.setMnemonic('w');

    JPanel rootPanel = new JPanel(new GridBagLayout());
    GridBag g = new GridBag()
      .setDefaultInsets(new Insets(0, 6, DEFAULT_VGAP, DEFAULT_HGAP))
      .setDefaultAnchor(GridBagConstraints.LINE_START)
      .setDefaultFill(GridBagConstraints.HORIZONTAL);

    rootPanel.add(icon, g.nextLine().next().coverColumn(4));
    rootPanel.add(description, g.next());
    rootPanel.add(readMore, g.nextLine().next().next());
    rootPanel.add(additionalDescription, g.nextLine().next().next().pady(DEFAULT_HGAP));
    rootPanel.add(myDontWarn,  g.nextLine().next().next().insets(0, 0, 0, 0));

    return rootPanel;

  }

  public boolean dontWarnAgain() {
    return myDontWarn.isSelected();
  }

  @Nullable
  @Override
  protected String getHelpId() {
    return "reference.VersionControl.Git.CrlfWarning";
  }

}
