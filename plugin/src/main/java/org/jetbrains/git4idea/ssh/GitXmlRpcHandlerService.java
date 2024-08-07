/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.git4idea.ssh;

import com.trilead.ssh2.ProxyData;
import consulo.builtinWebServer.BuiltInServerManager;
import consulo.builtinWebServer.xml.XmlRpcServer;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.util.nodep.io.FileUtilRt;
import org.apache.commons.codec.DecoderException;
import org.apache.ws.commons.serialize.DOMSerializer;
import org.apache.xmlrpc.XmlRpcConfig;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.jetbrains.git4idea.rt.GitExternalApp;
import org.jetbrains.git4idea.util.ScriptGenerator;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>The provider of external application scripts called by Git when a remote operation needs communication with the user.</p>
 * <p>
 * Usage:
 * <ol>
 * <li>Get the script from {@link #getScriptPath()}.</li>
 * <li>Set up proper environment variable
 * (e.g. {@code GIT_SSH} for SSH connections, or {@code GIT_ASKPASS} for HTTP) pointing to the script.</li>
 * <li>{@link #registerHandler(Object) Register} the handler of Git requests.</li>
 * <li>Call Git operation.</li>
 * <li>If the operation requires user interaction, the registered handler is called via XML RPC protocol.
 * It can show a dialog in the GUI and return the answer via XML RPC to the external application, that further provides
 * this value to the Git process.</li>
 * <li>{@link #unregisterHandler(int) Unregister} the handler after operation has completed.</li>
 * </ol>
 * </p>
 */
public abstract class GitXmlRpcHandlerService<T> {

  @Nonnull
  private final String myScriptTempFilePrefix;
  @Nonnull
  private final String myHandlerName;
  @Nonnull
  private final Class<? extends GitExternalApp> myScriptMainClass;

  @Nullable
  private File myScriptPath;
  @Nonnull
  private final Object SCRIPT_FILE_LOCK = new Object();

  @Nonnull
  private final Map<UUID, T> handlers = new HashMap<>();
  @Nonnull
  private final Object HANDLERS_LOCK = new Object();

  /**
   * @param handlerName Returns the name of the handler to be used by XML RPC client to call remote methods of a proper object.
   * @param aClass      Main class of the external application invoked by Git,
   *                    which is able to handle its requests and pass to the main IDEA instance.
   */
  protected GitXmlRpcHandlerService(@Nonnull String prefix, @Nonnull String handlerName, @Nonnull Class<? extends GitExternalApp> aClass) {
    myScriptTempFilePrefix = prefix;
    myHandlerName = handlerName;
    myScriptMainClass = aClass;
  }

  /**
   * @return the port number for XML RCP
   */
  public int getXmlRcpPort() {
    return BuiltInServerManager.getInstance().getPort();
  }

  /**
   * Get file to the script service
   *
   * @return path to the script
   * @throws IOException if script cannot be generated
   */
  @Nonnull
  public File getScriptPath() throws IOException {
    ScriptGenerator generator = new ScriptGenerator(myScriptTempFilePrefix, myScriptMainClass);
    generator.addClasses(XmlRpcClient.class,
                         XmlRpcConfig.class,
                         DOMSerializer.class,
                         DecoderException.class,
                         ProxyData.class,
                         FileUtilRt.class);
    customizeScriptGenerator(generator);

    synchronized (SCRIPT_FILE_LOCK) {
      if (myScriptPath == null || !myScriptPath.exists()) {
        myScriptPath = generator.generate();
      }
      return myScriptPath;
    }
  }

  /**
   * Adds more classes or resources to the script if needed.
   */
  protected abstract void customizeScriptGenerator(@Nonnull ScriptGenerator generator);

  /**
   * Register handler. Note that handlers must be unregistered using {@link #unregisterHandler(int)}.
   *
   * @param handler          a handler to register
   * @param parentDisposable a disposable to unregister the handler if it doesn't get unregistered manually
   * @return an identifier to pass to the environment variable
   */
  public UUID registerHandler(@Nonnull T handler, @Nonnull Disposable parentDisposable) {
    synchronized (HANDLERS_LOCK) {
      XmlRpcServer xmlRpcServer = XmlRpcServer.getInstance();
      if (!xmlRpcServer.hasHandler(myHandlerName)) {
        xmlRpcServer.addHandler(myHandlerName, createRpcRequestHandlerDelegate());
      }

      final UUID key = UUID.randomUUID();
      handlers.put(key, handler);
      Disposer.register(parentDisposable, new Disposable() {
        @Override
        public void dispose() {
          handlers.remove(key);
        }
      });
      return key;
    }
  }

  /**
   * Creates an implementation of the xml rpc handler, which methods will be called from the external application.
   * This method should just delegate the call to the specific handler of type {@link T}, which can be achieved by {@link #getHandler(int)}.
   *
   * @return New instance of the xml rpc handler delegate.
   */
  @Nonnull
  protected abstract Object createRpcRequestHandlerDelegate();

  /**
   * Get handler for the key
   *
   * @param key the key to use
   * @return the registered handler
   */
  @Nonnull
  protected T getHandler(UUID key) {
    synchronized (HANDLERS_LOCK) {
      T rc = handlers.get(key);
      if (rc == null) {
        throw new IllegalStateException("No handler for the key " + key);
      }
      return rc;
    }
  }

  /**
   * Unregister handler by the key
   *
   * @param key the key to unregister
   */
  public void unregisterHandler(UUID key) {
    synchronized (HANDLERS_LOCK) {
      if (handlers.remove(key) == null) {
        throw new IllegalArgumentException("The handler " + key + " is not registered");
      }
    }
  }

}
