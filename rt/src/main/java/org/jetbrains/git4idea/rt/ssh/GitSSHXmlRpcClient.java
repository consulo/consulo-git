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
package org.jetbrains.git4idea.rt.ssh;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.XmlRpcHttpClientConfig;

import jakarta.annotation.Nullable;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * Client for IDEA SSH GUI event handler
 */
@SuppressWarnings({"UseOfObsoleteCollectionType"})
public class GitSSHXmlRpcClient implements GitSSHHandler
{
	/**
	 * XML RPC client
	 */
	@Nullable
	private final XmlRpcClient myClient;

	/**
	 * A constructor
	 *
	 * @param port      port number
	 * @param batchMode if true, the client is run in the batch mode, so nothing should be prompted
	 * @throws IOException if there is IO problem
	 */
	GitSSHXmlRpcClient(final int port, final boolean batchMode) throws IOException
	{
		//noinspection HardCodedStringLiteral
		if(batchMode)
		{
			myClient = null;
		}
		else
		{
			XmlRpcClientConfigImpl clientConfig = new XmlRpcClientConfigImpl();
			clientConfig.setEncoding("UTF-8");
			clientConfig.setServerURL(new URL("http://127.0.0.1:" + port + "/RPC2"));

			myClient = new XmlRpcClient();
			myClient.setConfig(clientConfig);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	public boolean verifyServerHostKey(String token, final String hostname, final int port, final String serverHostKeyAlgorithm, final String serverHostKey, final boolean isNew)
	{
		if(myClient == null)
		{
			return false;
		}
		List parameters = new ArrayList<>();
		parameters.add(token);
		parameters.add(hostname);
		parameters.add(port);
		parameters.add(serverHostKeyAlgorithm);
		parameters.add(serverHostKey);
		parameters.add(isNew);
		try
		{
			return ((Boolean) myClient.execute(methodName("verifyServerHostKey"), parameters)).booleanValue();
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	/**
	 * Get the full method name
	 *
	 * @param method short name of the method
	 * @return full method name
	 */
	private static String methodName(final String method)
	{
		return GitSSHHandler.HANDLER_NAME + "." + method;
	}

	/**
	 * {@inheritDoc}
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	public String askPassphrase(String token, final String username, final String keyPath, final boolean resetPassword, final String lastError)
	{
		if(myClient == null)
		{
			return null;
		}
		Vector parameters = new Vector();
		parameters.add(token);
		parameters.add(username);
		parameters.add(keyPath);
		parameters.add(resetPassword);
		parameters.add(lastError);
		try
		{
			return adjustNull(((String) myClient.execute(methodName("askPassphrase"), parameters)));
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	public List<String> replyToChallenge(String token,
										   final String username,
										   final String name,
										   final String instruction,
										   final int numPrompts,
										   final Vector<String> prompt,
										   final Vector<Boolean> echo,
										   final String lastError)
	{
		if(myClient == null)
		{
			return null;
		}
		List parameters = new ArrayList();
		parameters.add(token);
		parameters.add(username);
		parameters.add(name);
		parameters.add(instruction);
		parameters.add(numPrompts);
		parameters.add(prompt);
		parameters.add(echo);
		parameters.add(lastError);
		try
		{
			return adjustNull((List<String>) myClient.execute(methodName("replyToChallenge"), parameters));
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	public String askPassword(String token, final String username, final boolean resetPassword, final String lastError)
	{
		if(myClient == null)
		{
			return null;
		}
		Vector parameters = new Vector();
		parameters.add(token);
		parameters.add(username);
		parameters.add(resetPassword);
		parameters.add(lastError);
		try
		{
			return adjustNull(((String) myClient.execute(methodName("askPassword"), parameters)));
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public String setLastSuccessful(String token, String userName, String method, String error)
	{
		if(myClient == null)
		{
			return "";
		}
		Vector parameters = new Vector();
		parameters.add(token);
		parameters.add(userName);
		parameters.add(method);
		parameters.add(error);
		try
		{
			return (String) myClient.execute(methodName("setLastSuccessful"), parameters);
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@SuppressWarnings("unchecked")
	public String getLastSuccessful(String token, String userName)
	{
		if(myClient == null)
		{
			return "";
		}
		Vector parameters = new Vector();
		parameters.add(token);
		parameters.add(userName);
		try
		{
			return (String) myClient.execute(methodName("getLastSuccessful"), parameters);
		}
		catch(XmlRpcException e)
		{
			XmlRpcHttpClientConfig clientConfig = (XmlRpcHttpClientConfig) myClient.getConfig();
			log("getLastSuccessful failed. token: " + token + ", userName: " + userName + ", client: " + clientConfig.getServerURL());
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	/**
	 * Since XML RPC client does not understand null values, the value should be
	 * adjusted (The password is {@code "-"} if null, {@code "+"+s) if non-null).
	 *
	 * @param s a value to adjust
	 * @return adjusted value.
	 */
	@Nullable
	private static String adjustNull(final String s)
	{
		return s.charAt(0) == '-' ? null : s.substring(1);
	}

	/**
	 * Since XML RPC client does not understand null values, the value should be
	 * adjusted. This is done by replacing empty array with null.
	 *
	 * @param s a value to adjust
	 * @return adjusted value.
	 */
	@Nullable
	private static <T> List<T> adjustNull(final List<T> s)
	{
		return s.size() == 0 ? null : s;
	}

	private static void log(String s)
	{
		System.err.println(s);
	}
}
