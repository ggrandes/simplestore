/*
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
 *
 */
package org.javastack.simplestore;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Simple Idempotent RESTful PUT/GET/DELETE key/value store
 * 
 * @author Guillermo Grandes / guillermo.grandes[at]gmail.com
 */
public class SimpleStoreServlet extends HttpServlet {
	private static final String CONF_FILE = "/simplestore.properties";
	private static final String STORAGE_PARAM = "org.javastack.simplestore.directory";
	private static final long serialVersionUID = 42L;
	private File storeDir = null;

	public SimpleStoreServlet() {
	}

	@Override
	public void init() throws ServletException {
		// Get Config param
		try {
			storeDir = new File(getConfig(STORAGE_PARAM, null)).getCanonicalFile();
			log("Storage Path: " + storeDir);
			storeDir.mkdirs();
		} catch (Exception e) {
			throw new ServletException(e);
		}
	}

	private final String getConfig(final String paramName, final String defValue) throws ServletException {
		String value = null;
		// Try Context Property
		if (value == null) {
			try {
				value = getServletContext().getInitParameter(paramName);
			} catch (Exception e) {
			}
		}
		// Try System Property
		if (value == null) {
			try {
				value = System.getProperty(paramName);
			} catch (Exception e) {
			}
		}
		// Try System Environment
		if (value == null) {
			try {
				value = System.getenv(paramName);
			} catch (Exception e) {
			}
		}
		// Try Config file
		if (value == null) {
			final Properties p = new Properties();
			InputStream is = null;
			try {
				log("Searching " + CONF_FILE.substring(1) + " in classpath");
				is = this.getClass().getResourceAsStream(CONF_FILE);
				if (is != null) {
					p.load(is);
					is.close();
				}
			} catch (Exception e) {
				throw new ServletException(e);
			}
			log("Searching " + paramName + " in config file");
			value = p.getProperty(paramName);
		}
		// Default value
		if (value == null) {
			value = defValue;
		}
		// Throw Error
		if (value == null) {
			throw new ServletException("Invalid param for: " + paramName);
		}
		return value;
	}

	@Override
	public void destroy() {
	}

	@Override
	protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		doGetHead(request, response, true);
	}

	@Override
	protected void doHead(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		doGetHead(request, response, false);
	}

	private void doGetHead(final HttpServletRequest request, final HttpServletResponse response,
			final boolean wantBody) throws IOException {
		final String key = getPathInfoKey(request.getPathInfo());
		if (key == null) {
			log("Invalid request: path=null");
			final PrintWriter out = response.getWriter();
			sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
			return;
		}
		InputStream is = null;
		OutputStream os = null;
		try {
			final File f = fileForKey(key);
			if (!f.isFile()) {
				throw new FileNotFoundException();
			}
			final long ifModifiedSince = request.getDateHeader("If-Modified-Since");
			if ((ifModifiedSince > 0) && (f.lastModified() <= ifModifiedSince)) {
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
				response.setContentLength(0);
				setLightCache(response);
				return;
			}
			String mimeType = request.getServletContext().getMimeType(key);
			if (mimeType == null) {
				mimeType = getMimeType(key);
			}
			if (mimeType != null) {
				response.setContentType(mimeType);
			}
			response.setDateHeader("Last-Modified", f.lastModified());
			setLightCache(response);
			response.setHeader("Content-Length", String.valueOf(f.length()));
			if (wantBody) {
				if (!useAIO(request, response, f)) {
					is = openInput(f);
					os = response.getOutputStream();
					copyStream(is, os);
				}
			}
		} catch (FileNotFoundException e) {
			final PrintWriter out = response.getWriter();
			log("Invalid request: file not found");
			sendResponse(response, out, HttpServletResponse.SC_NOT_FOUND, "Not Found");
			return;
		} catch (Exception e) {
			log("Invalid request: " + e.toString(), e);
			final PrintWriter out = response.getWriter();
			sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
			return;
		} finally {
			closeQuietly(os);
			closeQuietly(is);
		}
	}

	@Override
	protected void doPut(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		final PrintWriter out = response.getWriter();
		final String key = getPathInfoKey(request.getPathInfo());
		if (key == null) {
			log("Invalid request: path=null");
			sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
			return;
		}
		InputStream is = null;
		OutputStream os = null;
		try {
			final File f = fileForKey(key);
			is = request.getInputStream();
			os = openOutput(f);
			copyStream(is, os);
			sendResponse(response, out, HttpServletResponse.SC_OK, "updated");
		} catch (Exception e) {
			log("Invalid request: " + e.toString(), e);
			sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
			return;
		} finally {
			closeQuietly(os);
			closeQuietly(is);
		}
	}

	@Override
	protected void doDelete(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		final PrintWriter out = response.getWriter();
		final String key = getPathInfoKey(request.getPathInfo());
		if (key == null) {
			log("Invalid request: path=null");
			sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
			return;
		}
		try {
			final File f = fileForKey(key);
			if (!f.isFile()) {
				sendResponse(response, out, HttpServletResponse.SC_NOT_FOUND, "Not Found");
				return;
			}
			if (f.delete()) {
				sendResponse(response, out, HttpServletResponse.SC_OK, "deleted");
				return;
			}
		} catch (Exception e) {
			log("Invalid request: " + e.toString(), e);
		}
		sendResponse(response, out, HttpServletResponse.SC_BAD_REQUEST, "Bad request");
	}

	private final File fileForKey(final String key) throws IOException {
		final File f = new File(storeDir, key).getCanonicalFile();
		final String root = storeDir.getPath() + File.separatorChar;
		if (!f.getPath().startsWith(root)) {
			log("INVALID PATH: key=" + key + " (" + f.getPath() + ")");
			throw new FileNotFoundException("Invalid path");
		}
		// log("Resolved key=" + key + " to=" + f.getPath() + " chroot=" + storeDir.getPath());
		return f;
	}

	private final InputStream openInput(final File inFile) throws IOException {
		return new FileInputStream(inFile);
	}

	private final OutputStream openOutput(final File outFile) throws IOException {
		return new FileOutputStream(outFile);
	}

	private static final void closeQuietly(final Closeable is) {
		if (is != null) {
			try {
				is.close();
			} catch (IOException e) {
			}
		}
	}

	private final boolean useAIO(final HttpServletRequest request, final HttpServletResponse response,
			final File sendFile) throws IOException {
		final boolean aio = Boolean.TRUE.equals(request.getAttribute("org.apache.tomcat.sendfile.support"));
		if (aio) {
			request.setAttribute("org.apache.tomcat.sendfile.filename", sendFile.getAbsolutePath());
			request.setAttribute("org.apache.tomcat.sendfile.start", Long.valueOf(0L));
			request.setAttribute("org.apache.tomcat.sendfile.end", Long.valueOf(sendFile.length()));
			response.setHeader("X-sendfile", "true");
			response.flushBuffer();
		}
		return aio;
	}

	private static final void copyStream(final InputStream is, final OutputStream os) throws IOException {
		final byte[] b = new byte[4096];
		while (true) {
			final int rlen = is.read(b);
			if (rlen < 0)
				break;
			os.write(b, 0, rlen);
		}
		os.flush();
	}

	private static final void setNoCache(final HttpServletResponse response) {
		response.setHeader("Cache-Control", "private, no-cache, no-store");
		response.setHeader("Pragma", "no-cache");
	}

	private static final void setLightCache(final HttpServletResponse response) {
		response.setHeader("Cache-Control", "must-revalidate, max-age=1");
		response.setHeader("Pragma", "no-cache");
	}

	private static final void sendResponse(final HttpServletResponse response, final PrintWriter out,
			final int status, final String msg) {
		final StringBuilder sb = new StringBuilder(36 + msg.length());
		sb.append("{\"status\":\"");
		sb.append(((status >= 400) && (status <= 599)) ? "error" : "success");
		sb.append("\",\"message\":\"").append(msg).append("\"}\r\n");
		response.setContentType("application/json");
		response.setContentLength(sb.length());
		setNoCache(response);
		out.print(sb.toString());
		out.flush();
	}

	private static final String getPathInfoKey(String pathInfo) {
		if (pathInfo == null)
			return null;
		if (pathInfo.isEmpty())
			return null;
		if (pathInfo.charAt(0) == '/')
			pathInfo = pathInfo.substring(1);
		if (!checkSafeURLString(pathInfo))
			return null;
		return pathInfo;
	}

	private static final boolean checkSafeURLString(final String in) {
		final int len = in.length();
		for (int i = 0; i < len; i++) {
			final char c = in.charAt(i);
			// [A-Za-z0-9._-]+
			if ((c >= 'A') && (c <= 'Z'))
				continue;
			if ((c >= 'a') && (c <= 'z'))
				continue;
			if ((c >= '0') && (c <= '9'))
				continue;
			switch (c) {
				case '.':
				case '_':
				case '-':
					continue;
			}
			return false;
		}
		return true;
	}

	private static final String getMimeType(final String name) {
		if (name.endsWith(".properties"))
			return "text/x-java-properties";
		if (name.endsWith(".yaml") || name.endsWith(".yml"))
			return "text/yaml";
		if (name.endsWith(".json"))
			return "application/json";
		if (name.endsWith(".md"))
			return "text/markdown";
		return null;
	}
}
