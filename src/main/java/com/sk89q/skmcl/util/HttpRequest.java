/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010, 2011 Albert Pham <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.skmcl.util;

import com.sk89q.mclauncher.util.LauncherUtils;
import org.codehaus.jackson.map.ObjectMapper;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sk89q.mclauncher.util.LauncherUtils.checkInterrupted;

/**
 * A simple fluent interface for performing HTTP requests that uses
 * {@link java.net.HttpURLConnection} or {@link HttpsURLConnection}.
 */
public class HttpRequest implements Closeable {

    private static final int READ_TIMEOUT = 1000 * 60 * 10;
    private static final int READ_BUFFER_SIZE = 1024 * 8;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> headers = new HashMap<String, String>();
    private final String method;
    private final URL url;
    private String contentType;
    private byte[] body;
    private HttpURLConnection conn;
    private InputStream inputStream;

    /**
     * Create a new HTTP request.
     *
     * @param method the method
     * @param url the URL
     */
    private HttpRequest(String method, URL url) {
        this.method = method;
        this.url = url;
    }

    /**
     * Set the content body to a JSON object with the content type of "application/json".
     *
     * @param object the object to serialize as JSON
     * @return this object
     * @throws IOException if the object can't be mapped
     */
    public HttpRequest bodyJson(Object object) throws IOException {
        contentType = "application/json";
        body = mapper.writeValueAsBytes(object);
        return this;
    }

    /**
     * Submit form data.
     *
     * @param form the form
     * @return this object
     */
    public HttpRequest bodyForm(Form form) {
        contentType = "application/x-www-form-urlencoded";
        body = form.toString().getBytes();
        return this;
    }

    /**
     * Add a header.
     *
     * @param key the header key
     * @param value the header value
     * @return this object
     */
    public HttpRequest header(String key, String value) {
        headers.put(key, value);
        return this;
    }

    /**
     * Execute the request.
     *
     * After execution, {@link #close()} should be called.
     *
     * @return this object
     * @throws IOException on I/O error
     */
    public HttpRequest execute() throws IOException {
        boolean successful = false;

        try {
            if (conn != null) {
                throw new IllegalArgumentException("Connection already executed");
            }

            conn = (HttpURLConnection) url.openConnection();

            if (body != null) {
                conn.setRequestProperty("Content-Type", contentType);
                conn.setRequestProperty("Content-Length", Integer.toString(body.length));
                conn.setDoInput(true);
            }

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            conn.setRequestMethod(method);
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setReadTimeout(READ_TIMEOUT);

            conn.connect();

            if (body != null) {
                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                out.write(body);
                out.flush();
                out.close();
            }

            inputStream = conn.getResponseCode() == HttpURLConnection.HTTP_OK ?
                    conn.getInputStream() : conn.getErrorStream();

            successful = true;
        } finally {
            if (!successful) {
                close();
            }
        }

        return this;
    }

    /**
     * Require that the response code is one of the given response codes.
     *
     * @param codes a list of codes
     * @return this object
     * @throws IOException if there is an I/O error or the response code is not expected
     */
    public HttpRequest expectResponseCode(int ... codes) throws IOException {
        int responseCode = getResponseCode();

        for (int code : codes) {
            if (code == responseCode) {
                return this;
            }
        }

        close();
        throw new IOException("Did not get expected response code, got " + responseCode);
    }

    /**
     * Get the response code.
     *
     * @return the response code
     * @throws IOException on I/O error
     */
    public int getResponseCode() throws IOException {
        if (conn == null) {
            throw new IllegalArgumentException("No connection has been made");
        }

        return conn.getResponseCode();
    }

    /**
     * Buffer the returned response.
     *
     * @return the buffered response
     * @throws IOException
     */
    public BufferedResponse returnContent() throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("No input stream available");
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int b = 0;
            while ((b = inputStream.read()) != -1) {
                bos.write(b);
            }
            return new BufferedResponse(bos.toByteArray());
        } finally {
            close();
        }
    }

    /**
     * Save the result to a file.
     *
     * @param file the file
     * @return this object
     * @throws IOException on I/O error
     * @throws InterruptedException on interruption
     */
    public HttpRequest saveContent(File file) throws IOException, InterruptedException {
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;

        try {
            fos = new FileOutputStream(file);
            bos = new BufferedOutputStream(fos);

            saveContent(bos);
        } finally {
            LauncherUtils.close(bos);
            LauncherUtils.close(fos);
        }

        return this;
    }

    /**
     * Save the result to an output stream.
     *
     * @param out the output stream
     * @return this object
     * @throws IOException on I/O error
     * @throws InterruptedException on interruption
     */
    public HttpRequest saveContent(OutputStream out) throws IOException, InterruptedException {
        BufferedInputStream bis;

        try {
            bis = new BufferedInputStream(inputStream);

            byte[] data = new byte[READ_BUFFER_SIZE];
            int len = 0;
            while ((len = bis.read(data, 0, READ_BUFFER_SIZE)) >= 0) {
                out.write(data, 0, len);
                checkInterrupted();
            }
        } finally {
            close();
        }

        return this;
    }

    /**
     * Perform a GET request.
     *
     * @param url the URL
     * @return a new request object
     */
    public static HttpRequest get(URL url) {
        return request("GET", url);
    }

    /**
     * Perform a POST request.
     *
     * @param url the URL
     * @return a new request object
     */
    public static HttpRequest post(URL url) {
        return request("POST", url);
    }

    /**
     * Perform a request.
     *
     * @param method the method
     * @param url the URL
     * @return a new request object
     */
    public static HttpRequest request(String method, URL url) {
        return new HttpRequest(method, url);
    }

    /**
     * Create a new {@link URL} and throw a {@link RuntimeException} if the URL
     * is not valid.
     *
     * @param url the url
     * @return a URL object
     * @throws RuntimeException if the URL is invalid
     */
    public static URL url(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (conn != null) conn.disconnect();
    }

    /**
     * Used with {@link #bodyForm(Form)}.
     */
    public final static class Form {
        public final List<String> elements = new ArrayList<String>();

        private Form() {
        }

        /**
         * Add a key/value to the form.
         *
         * @param key the key
         * @param value the value
         * @return this object
         */
        public Form add(String key, String value) {
            try {
                elements.add(URLEncoder.encode(key, "UTF-8") +
                        "=" + URLEncoder.encode(value, "UTF-8"));
                return this;
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (String element : elements) {
                if (first) {
                    first = false;
                } else {
                    builder.append("&");
                }
                builder.append(element);
            }
            return builder.toString();
        }

        /**
         * Create a new form.
         *
         * @return a new form
         */
        public static Form form() {
            return new Form();
        }
    }

    /**
     * Used to buffer the response in memory.
     */
    public class BufferedResponse {
        private final byte[] data;

        private BufferedResponse(byte[] data) {
            this.data = data;
        }


        /**
         * Return the result as a string.
         *
         * @param encoding the encoding
         * @return the string
         * @throws IOException on I/O error
         */
        public String asString(String encoding) throws IOException {
            return new String(data, encoding);
        }

        /**
         * Return the result as an instance of the given class that has been
         * deserialized from a JSON payload.
         *
         * @return the object
         * @throws IOException on I/O error
         */
        public <T> T asJson(Class<T> cls) throws IOException {
            return mapper.readValue(asString("UTF-8"), cls);
        }

        /**
         * Return the result as an instance of the given class that has been
         * deserialized from a XML payload.
         *
         * @return the object
         * @throws IOException on I/O error
         */
        public <T> T asXml(Class<T> cls) throws IOException {
            try {
                JAXBContext context = JAXBContext.newInstance(cls);
                Unmarshaller um = context.createUnmarshaller();
                return (T) um.unmarshal(new ByteArrayInputStream(data));
            } catch (JAXBException e) {
                throw new IOException(e);
            }
        }

        /**
         * Save the result to a file.
         *
         * @param file the file
         * @return this object
         * @throws IOException on I/O error
         * @throws InterruptedException on interruption
         */
        public BufferedResponse saveContent(File file) throws IOException, InterruptedException {
            FileOutputStream fos = null;
            BufferedOutputStream bos = null;

            file.getParentFile().mkdirs();

            try {
                fos = new FileOutputStream(file);
                bos = new BufferedOutputStream(fos);

                saveContent(bos);
            } finally {
                LauncherUtils.close(bos);
                LauncherUtils.close(fos);
            }

            return this;
        }

        /**
         * Save the result to an output stream.
         *
         * @param out the output stream
         * @return this object
         * @throws IOException on I/O error
         * @throws InterruptedException on interruption
         */
        public BufferedResponse saveContent(OutputStream out) throws IOException, InterruptedException {
            out.write(data);

            return this;
        }
    }
}