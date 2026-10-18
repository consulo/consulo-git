package git4idea;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.ui.ex.awt.DialogWrapper;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;

/**
 * Use {@link DialogManager#show(DialogWrapper) DialogManager.show(DialogWrapper)} instead of {@link DialogWrapper#show()}
 * to make the code testable:
 * in the test environment such calls will be transferred to the TestDialogManager and can be handled by tests;
 * in the production environment they will be simply delegated to DialogWrapper#show().
 *
 * @author Kirill Likhodedov
 */
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class DialogManager {
  public static void show(@Nonnull DialogWrapper dialog) {
    dialogManager().showDialog(dialog);
  }

  protected void showDialog(@Nonnull DialogWrapper dialog) {
    dialog.show();
  }

  @Nonnull
  private static DialogManager dialogManager() {
    return Application.get().getInstance(DialogManager.class);
  }
}
