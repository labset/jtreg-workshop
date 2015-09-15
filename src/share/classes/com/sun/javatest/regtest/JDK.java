/*
 * Copyright (c) 2007, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.javatest.regtest;

import com.sun.javatest.regtest.agent.GetSystemProperty;
import com.sun.javatest.regtest.agent.JDK_Version;
import com.sun.javatest.regtest.agent.SearchPath;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Info about a JDK
 */
public class JDK {

    public static JDK of(String javaHome) {
        return of(new File(javaHome));
    }

    public static synchronized JDK of(File javaHome) {
        JDK jdk = cache.get(javaHome);
        if (jdk == null)
            cache.put(javaHome, jdk = new JDK(javaHome));
        return jdk;
    }

    private static final Map<File, JDK> cache = new HashMap<File, JDK>();

    private JDK(File jdk) {
        this.jdk = jdk;
        absJDK = jdk.getAbsoluteFile();
    }

    public File getFile() {
        return jdk;
    }

    public File getAbsoluteFile() {
        return absJDK;
    }

    public File getCanonicalFile() {
        try {
            return jdk.getCanonicalFile();
        } catch (IOException e) {
            return absJDK;
        }
    }

    public File getJavaProg() {
        return new File(new File(absJDK, "bin"), "java");
    }

    public File getJavacProg() {
        return new File(new File(absJDK, "bin"), "javac");
    }

    public File getToolsJar() {
        // for now, we always return the file, even if if does not exist;
        // it will automatically get filtered out if it is added to a SearchPath
        // and does not exist for this JDK.
        return new File(new File(absJDK, "lib"), "tools.jar");
    }

    public boolean exists() {
        return jdk.exists();
    }

    public String getPath() {
        return jdk.getPath();
    }

    public String getAbsolutePath() {
        return absJDK.getPath();
    }

    // only used for JDK 1.1
    public SearchPath getJavaClassPath() {
        File jh = absJDK;
        File jh_lib = new File(jh, "lib");

        return new SearchPath(
            new File(jh, "classes"),
            new File(jh_lib, "classes"),
            new File(jh_lib, "classes.zip"));
    }

    public SearchPath getJDKClassPath() {
        // will return an empty path if tools.jar does not exist
        return new SearchPath(getToolsJar());
    }

    // params just used for execMode and javatestClassPath
    JDK_Version getVersion(RegressionParameters params) {
        return getVersion(params.getExecMode(), params.getJavaTestClassPath());
    }

    JDK_Version getVersion(ExecMode mode, SearchPath getSysPropClassPath) {
        return JDK_Version.forName(getVersionAsString(mode, getSysPropClassPath));
    }

    private synchronized String getVersionAsString(ExecMode mode, SearchPath getSysPropClassPath) {
        if (version == null) {
            final String VERSION_PROPERTY = "java.specification.version";
            version = "unknown"; // default
            ProcessBuilder pb = new ProcessBuilder();
            // since we are trying to determine the Java version, we have to assume
            // the worst, and use CLASSPATH.
            pb.environment().put("CLASSPATH", getSysPropClassPath.toString());
            pb.command(getJavaProg().getPath(), GetSystemProperty.class.getName(), VERSION_PROPERTY);
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                String out = getOutput(p);
                int rc = p.waitFor();
                if (rc == 0) {
                    String[] v = StringUtils.splitEqual(out.trim());
                    if (v.length == 2 && v[0].equals(VERSION_PROPERTY))
                        version = v[1];
                }
            } catch (InterruptedException e) {
                // ignore, leave version as default
            } catch (IOException e) {
                // ignore, leave version as default
            }

            // java.specification.version is not defined in JDK1.1.*
            if (version == null || version.length() == 0)
                version = "1.1";
        }
        return version;
    }

    public synchronized String getFullVersion(Collection<String> vmOpts) {
        if (fullVersions == null)
            fullVersions = new HashMap<Set<String>, String>();

        Set<String> vmOptsSet = new LinkedHashSet<String>(vmOpts);
        String fullVersion = fullVersions.get(vmOptsSet);
        if (fullVersion == null) {
            fullVersion = jdk.getPath();  // default
            List<String> cmdArgs = new ArrayList<String>();
            cmdArgs.add(getJavaProg().getPath());
            cmdArgs.addAll(vmOpts);
            cmdArgs.add("-version");

            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                String out = getOutput(p);
                int rc = p.waitFor();
                if (rc == 0) {
                    fullVersion = "(" + jdk + ")" + LINESEP + out;
                }
            } catch (InterruptedException e) {
                // ignore, leave version as default
            } catch (IOException e) {
                // ignore, leave version as default
            }

            fullVersions.put(vmOptsSet, fullVersion);
        }

        return fullVersion;
    }

    public boolean hasModules() {
        return !getModules(Collections.<String>emptySet()).isEmpty();
    }

    // for now, we do a direct invocation of the JVM with -limitmods.
    // in time, we should merge this with the invocation to collect system properties
    // and other test-suite-specific values for @requires
    public synchronized Map<String,String> getModules(Collection<String> vmOpts) {
        if (modulesMap == null)
            modulesMap = new HashMap<Set<String>, Map<String,String>>();

        Set<String> vmOptsSet = new LinkedHashSet<String>(vmOpts);
        Map<String,String> modules = modulesMap.get(vmOptsSet);
        if (modules == null) {
            modules = new LinkedHashMap<String,String>();
            List<String> cmdArgs = new ArrayList<String>();
            cmdArgs.add(getJavaProg().getPath());
            cmdArgs.addAll(vmOpts);
            cmdArgs.add("-listmods");

            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                List<String> lines = getOutputLines(p);
                int rc = p.waitFor();
                // note: -listmods typically returns rc=1; ignore for now
                Pattern modulePattern = Pattern.compile("^([A-Za-z][A-Za-z0-9._]*)(@[0-9][0-9.]*| *\\([^)]*\\))$");
                for (String line: lines) {
                    Matcher m = modulePattern.matcher(line);
                    if (m.matches()) {
                        modules.put(m.group(1), line);
                    }
                }
            } catch (InterruptedException e) {
                // ignore
            } catch (IOException e) {
                // ignore
            }

            modulesMap.put(vmOptsSet, modules);
        }

        return modules;
    }

    public synchronized Properties getSystemProperties(RegressionParameters params) {
        if (sysPropsMap == null)
            sysPropsMap = new HashMap<Set<String>, Properties>();

        List<String> vmOpts = params.getTestVMJavaOptions();
        Set<String> vmOptsSet = new LinkedHashSet<String>(vmOpts);
        Properties sysProps = sysPropsMap.get(vmOptsSet);
        if (sysProps == null) {
            sysProps = new Properties();
            List<String> cmdArgs = new ArrayList<String>();
            cmdArgs.add(getJavaProg().getPath());
                        // set classpath via env variable
            cmdArgs.addAll(vmOpts);
            cmdArgs.add(GetSystemProperty.class.getName());
            cmdArgs.add("-all");

            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            // since we are trying to determine the Java version, we have to assume
            // the worst, and use CLASSPATH.
            pb.environment().put("CLASSPATH", params.getJavaTestClassPath().toString());
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                sysProps.load(p.getInputStream());
                int rc = p.waitFor();
                if (rc != 0) {
                    System.err.println("could not get system properties for " +
                            getJavaProg() + " " + vmOpts);
                }
            } catch (InterruptedException e) {
                // ignore, leave properties undefined
            } catch (IOException e) {
                // ignore, leave properties undefined
            }

            sysPropsMap.put(vmOptsSet, sysProps);
        }

        return sysProps;
    }

    private String getOutput(Process p) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return e.toString();
        }
    }

    private List<String> getOutputLines(Process p) {
        try {
            List<String> lines = new ArrayList<String>();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return getPath();
    }

    @Override
    public int hashCode() {
        return absJDK.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof JDK))
            return false;
        JDK other = (JDK) o;
        return absJDK.equals(other.absJDK);
    }

    private final File jdk;
    private final File absJDK;

    /** Value of java.specification.version for this JDK. Lazily evaluated as needed. */
    private String version;
    /** Value of java VMOPTS -version for this JDK. Lazily evaluated as needed. */
    private Map<Set<String>, String> fullVersions;
    /** System properties java VMOPTS -version for this JDK. Lazily evaluated as needed. */
    private Map<Set<String>, Properties> sysPropsMap;
    /** Modules for this JDK. Lazily evaluated as needed. */
    private Map<Set<String>, Map<String,String>> modulesMap;

    private static final String LINESEP  = System.getProperty("line.separator");
}
