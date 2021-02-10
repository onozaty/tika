/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.server.core;

import org.apache.commons.cli.CommandLine;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.utils.ProcessUtils;
import org.apache.tika.utils.StringUtils;
import org.apache.tika.utils.XMLReaderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TikaServerConfig {

    //used in fork mode -- restart after processing this many files
    private static final long DEFAULT_MAX_FILES = 100000;


    public static final int DEFAULT_PORT = 9998;
    private static final int DEFAULT_DIGEST_MARK_LIMIT = 20*1024*1024;
    public static final String DEFAULT_HOST = "localhost";
    public static final Set<String> LOG_LEVELS = new HashSet<>(Arrays.asList("debug", "info"));

    private static final String UNSECURE_WARNING =
            "WARNING: You have chosen to run tika-server with unsecure features enabled.\n"+
                    "Whoever has access to your service now has the same read permissions\n"+
                    "as you've given your fetchers and the same write permissions as your emitters.\n" +
                    "Users could request and receive a sensitive file from your\n" +
                    "drive or a webpage from your intranet and/or send malicious content to\n" +
                    " your emitter endpoints.  See CVE-2015-3271.\n"+
                    "Please make sure you know what you are doing.";

    private static final List<String> ONLY_IN_FORK_MODE =
            Arrays.asList(new String[] { "taskTimeoutMillis", "taskPulseMillis",
                    "pingTimeoutMillis", "pingPulseMillis", "maxFiles", "javaHome", "maxRestarts",
                    "numRestarts",
                    "forkedStatusFile", "maxForkedStartupMillis", "tmpFilePrefix"});

    /**
     * Config with only the defaults
     */
    public static TikaServerConfig load() {
        return new TikaServerConfig();
    }

    public static TikaServerConfig load(CommandLine commandLine) throws IOException, TikaException {

        TikaServerConfig config = null;
        if (commandLine.hasOption("c")) {
            config = load(Paths.get(commandLine.getOptionValue("c")));
            config.setConfigPath(commandLine.getOptionValue("c"));
        } else {
            config = new TikaServerConfig();
        }

        //overwrite with the commandline
        if (commandLine.hasOption("p")) {
            int port = -1;
            try {
                config.setPort(Integer.parseInt(commandLine.getOptionValue("p")));
                config.setPortString(commandLine.getOptionValue("p"));
            } catch (NumberFormatException e) {
                config.setPortString(commandLine.getOptionValue("p"));
            }
        }
        if (commandLine.hasOption("h")) {
            config.setHost(commandLine.getOptionValue("h"));
        }

        if (commandLine.hasOption("i")) {
            config.setId(commandLine.getOptionValue("i"));
        }

        if (commandLine.hasOption("numRestarts")) {
            config.setNumRestarts(Integer.parseInt(commandLine.getOptionValue("numRestarts")));
        }

        if (commandLine.hasOption("forkedStatusFile")) {
            config.setForkedStatusFile(commandLine.getOptionValue("forkedStatusFile"));
        }
        config.validateConsistency();
        return config;
    }

    private void setPortString(String portString) {
        this.portString = portString;
    }

    private void setId(String id) {
        this.idBase = id;
    }

    public static TikaServerConfig load (Path p) throws IOException, TikaException {
        try (InputStream is = Files.newInputStream(p)) {
            return TikaServerConfig.load(is);
        }
    }

    public static TikaServerConfig load(InputStream is) throws IOException, TikaException {
        Node properties  = null;
        try {
            properties = XMLReaderUtils.buildDOM(is).getDocumentElement();
        } catch (SAXException e) {
            throw new IOException(e);
        }
        if (! properties.getLocalName().equals("properties")) {
            throw new TikaConfigException("expect settings as root node");
        }
        NodeList children = properties.getChildNodes();
        TikaServerConfig config = new TikaServerConfig();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if ("server".equals(child.getLocalName())) {
                loadServerConfig(child, config);
            }
        }
        config.validateConsistency();
        return config;
    }

    private static void loadServerConfig(Node server, TikaServerConfig config)
            throws TikaConfigException {
        NodeList params = server.getChildNodes();
        for (int i = 0; i < params.getLength(); i++) {
            Node param = params.item(i);
            String localName = param.getLocalName();
            String txt = param.getTextContent();
            if ("endpoints".equals(localName)) {
                config.addEndPoints(loadStringList("endpoint", param.getChildNodes()));
            } else if ("forkedJVMArgs".equals(localName)) {
                config.addJVMArgs(loadStringList("arg", param.getChildNodes()));
            } else if (localName != null && txt != null) {
                if ("port".equals(localName)) {
                    config.setPortString(txt);
                } else {
                    tryToSet(config, localName, txt);
                }
            }
        }
    }

    private static void tryToSet(TikaServerConfig config, String localName, String txt) throws TikaConfigException {
        String setter = "set"+localName.substring(0,1).toUpperCase(Locale.US)+localName.substring(1);
        Class[] types = new Class[]{String.class, boolean.class, int.class, long.class};
        for (Class t : types) {
            try {
                Method m = TikaServerConfig.class.getMethod(setter, t);
                if (t == int.class) {
                    try {
                        m.invoke(config, Integer.parseInt(txt));
                        return;
                    } catch (IllegalAccessException|InvocationTargetException e) {
                        throw new TikaConfigException("bad parameter "+setter, e);
                    }
                } else if (t == long.class) {
                    try {
                        m.invoke(config, Long.parseLong(txt));
                        return;
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new TikaConfigException("bad parameter " + setter, e);
                    }
                } else if (t == boolean.class) {
                    try {
                        m.invoke(config, Boolean.parseBoolean(txt));
                        return;
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new TikaConfigException("bad parameter " + setter, e);
                    }
                } else {
                    try {
                        m.invoke(config, txt);
                        return;
                    } catch (IllegalAccessException|InvocationTargetException e) {
                        throw new TikaConfigException("bad parameter "+setter, e);
                    }
                }
            } catch (NoSuchMethodException e) {
                //swallow
            }
        }
        throw new TikaConfigException("Couldn't find setter: "+setter);
    }

    private static List<String> loadStringList(String itemName, NodeList nodelist) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < nodelist.getLength(); i++) {
            Node n = nodelist.item(i);
            if (itemName.equals(n.getLocalName())) {
                list.add(n.getTextContent());
            }
        }
        return list;
    }

        /*
    TODO: integrate these settings:
     * Number of milliseconds to wait to start forked process.
    public static final long DEFAULT_FORKED_PROCESS_STARTUP_MILLIS = 60000;

     * Maximum number of milliseconds to wait to shutdown forked process to allow
     * for current parses to complete.
    public static final long DEFAULT_FORKED_PROCESS_SHUTDOWN_MILLIS = 30000;

    private long forkedProcessStartupMillis = DEFAULT_FORKED_PROCESS_STARTUP_MILLIS;

    private long forkedProcessShutdownMillis = DEFAULT_FORKED_PROCESS_SHUTDOWN_MILLIS;

     */



    /**
     * If the forked process doesn't receive a ping or the parent doesn't
     * hear back from a ping in this amount of time, terminate and restart the forked process.
     */
    public static final long DEFAULT_PING_TIMEOUT_MILLIS = 30000;

    /**
     * How often should the parent try to ping the forked process to check status
     */
    public static final long DEFAULT_PING_PULSE_MILLIS = 500;

    /**
     * Number of milliseconds to wait per server task (parse, detect, unpack, translate,
     * etc.) before timing out and shutting down the forked process.
     */
    public static final long DEFAULT_TASK_TIMEOUT_MILLIS = 120000;

    /**
     * Number of milliseconds to wait for forked process to startup
     */
    public static final long DEFAULT_FORKED_STARTUP_MILLIS = 120000;

    private int maxRestarts = -1;
    private long maxFiles = 100000;
    private long taskTimeoutMillis = DEFAULT_TASK_TIMEOUT_MILLIS;
    private long pingTimeoutMillis = DEFAULT_PING_TIMEOUT_MILLIS;
    private long pingPulseMillis = DEFAULT_PING_PULSE_MILLIS;
    private long maxforkedStartupMillis = DEFAULT_FORKED_STARTUP_MILLIS;
    private boolean enableUnsecureFeatures = false;
    private String cors = "";
    private boolean returnStackTrace = false;
    private boolean noFork = false;
    private String tempFilePrefix = "tika-server-tmp-"; //can be set for debugging
    private List<String> forkedJvmArgs = new ArrayList<>();
    private String idBase = UUID.randomUUID().toString();
    private String portString = Integer.toString(DEFAULT_PORT);
    private int port = DEFAULT_PORT;
    private String host = DEFAULT_HOST;

    private int digestMarkLimit = DEFAULT_DIGEST_MARK_LIMIT;
    private String digest = "";
    //debug or info only
    private String logLevel = "";
    private Path configPath;
    private List<String> endPoints = new ArrayList<>();

    //these should only be set in the forked process
    //and they are automatically set by the forking process
    private String forkedStatusFile;
    private int numRestarts = 0;

    public boolean isNoFork() {
        return noFork;
    }

    public String getPortString() {
        return portString;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
    /**
     * How long to wait for a task before shutting down the forked server process
     * and restarting it.
     * @return
     */
    public long getTaskTimeoutMillis() {
        return taskTimeoutMillis;
    }

    /**
     *
     * @param taskTimeoutMillis number of milliseconds to allow per task
     *                          (parse, detection, unzipping, etc.)
     */
    public void setTaskTimeoutMillis(long taskTimeoutMillis) {
        this.taskTimeoutMillis = taskTimeoutMillis;
    }

    public long getPingTimeoutMillis() {
        return pingTimeoutMillis;
    }

    /**
     *
     * @param pingTimeoutMillis if the parent doesn't receive a response
     *                          in this amount of time, or
     *                          if the forked doesn't receive a ping
     *                          in this amount of time, restart the forked process
     */
    public void setPingTimeoutMillis(long pingTimeoutMillis) {
        this.pingTimeoutMillis = pingTimeoutMillis;
    }

    public long getPingPulseMillis() {
        return pingPulseMillis;
    }

    /**
     *
     * @param pingPulseMillis how often to test that the parent and/or forked is alive
     */
    public void setPingPulseMillis(long pingPulseMillis) {
        this.pingPulseMillis = pingPulseMillis;
    }

    public int getMaxRestarts() {
        return maxRestarts;
    }

    public void setMaxRestarts(int maxRestarts) {
        this.maxRestarts = maxRestarts;
    }

    public void setHost(String host) {
        if ("*".equals(host)) {
            host = "0.0.0.0";
        }
        this.host = host;
    }

    /**
     * Maximum time in millis to allow for the forked process to startup
     * or restart
     * @return
     */
    public long getMaxForkedStartupMillis() {
        return maxforkedStartupMillis;
    }

    public void setMaxForkedStartupMillis(long maxForkedStartupMillis) {
        this.maxforkedStartupMillis = maxForkedStartupMillis;
    }

    public List<String> getForkedProcessArgs(int port, String id) {
        //these are the arguments for the forked process
        List<String> args = new ArrayList<>();
        args.add("-p");
        args.add(Integer.toString(port));
        args.add("-i");
        args.add(id);
        if (hasConfigFile()) {
            args.add("-c");
            args.add(
                    ProcessUtils.escapeCommandLine(
                            configPath.toAbsolutePath().toString()));
        }
        return args;
    }

    public String getIdBase() {
        return idBase;
    }

    /**
     * full path to the java executable
     * @return
     */
    public String getJavaPath() {
        return "java";
    }

    public List<String> getForkedJvmArgs() {
        return forkedJvmArgs;
    }

    public String getTempFilePrefix() {
        return tempFilePrefix;
    }

    public boolean isEnableUnsecureFeatures() {
        return enableUnsecureFeatures;
    }

    private void validateConsistency() throws TikaConfigException {
        if (host == null) {
            throw new TikaConfigException("Must specify 'host'");
        }
        if (!StringUtils.isBlank(portString)) {
            try {
                setPort(Integer.parseInt(portString));
            } catch (NumberFormatException e) {

            }
        }
    }

    public String getHost() {
        return host;
    }

    public void setLogLevel(String level) throws TikaConfigException {
        if (level.equals("debug") || level.equals("info")) {
            this.logLevel = level;
        } else {
            throw new TikaConfigException("log level must be one of: 'debug' or 'info'");
        }
    }
    public String getLogLevel() {
        return logLevel;
    }

    /**
     *
     * @return the origin url for cors, can be "*"
     */
    public String getCors() {
        return cors;
    }

    public boolean hasConfigFile() {
        return configPath != null;
    }

    public void setConfigPath(String path) {
        this.configPath = Paths.get(path);
    }

    public Path getConfigPath() {
        return configPath;
    }

    public int getDigestMarkLimit() {
        return digestMarkLimit;
    }

    /**
     * digest configuration string, e.g. md5 or sha256, alternately w 16 or 32 encoding,
     * e.g. md5:32,sha256:16 would result in two digests per file
     * @return
     */
    public String getDigest() {
        return digest;
    }


    /**
     * maximum number of files before the forked server restarts.
     * This is useful for avoiding any slow-building memory leaks/bloat.
     * @return
     */
    public long getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(long maxFiles) {
        this.maxFiles = maxFiles;
    }

    public boolean isReturnStackTrace() {
        return returnStackTrace;
    }

    public void setReturnStackTrace(boolean returnStackTrace) {
        this.returnStackTrace = returnStackTrace;
    }

    public List<String> getEndPoints() {
        return endPoints;
    }

    public String getId() {
        //TODO fix this
        return idBase;
    }

    private void addEndPoints(List<String> endPoints) {
        this.endPoints.addAll(endPoints);
    }

    private void addJVMArgs(List<String> args) {
        this.forkedJvmArgs.addAll(args);
    }

    public void setEnableUnsecureFeatures(boolean enableUnsecureFeatures) {
        this.enableUnsecureFeatures = enableUnsecureFeatures;
    }

    /******
     * these should only be used in the commandline for a forked process
     ******/


    private void setNumRestarts(int numRestarts) {
        this.numRestarts = numRestarts;
    }

    public int getNumRestarts() {
        return numRestarts;
    }

    public String getForkedStatusFile() {
        return forkedStatusFile;
    }

    private void setForkedStatusFile(String forkedStatusFile) {
        this.forkedStatusFile = forkedStatusFile;
    }

}
