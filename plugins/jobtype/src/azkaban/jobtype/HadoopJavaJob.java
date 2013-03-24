package azkaban.jobtype;

/*
 * Copyright 2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Logger;

import azkaban.security.HadoopSecurityManager;
import azkaban.security.HadoopSecurityManagerException;
import azkaban.utils.Props;
import azkaban.jobExecutor.JavaProcessJob;

public class HadoopJavaJob extends JavaProcessJob {

	public static final String RUN_METHOD_PARAM = "method.run";
	public static final String CANCEL_METHOD_PARAM = "method.cancel";
	public static final String PROGRESS_METHOD_PARAM = "method.progress";

	public static final String JOB_CLASS = "job.class";
	public static final String DEFAULT_CANCEL_METHOD = "cancel";
	public static final String DEFAULT_RUN_METHOD = "run";
	public static final String DEFAULT_PROGRESS_METHOD = "getProgress";

	private static final String HADOOP_SECURITY_MANAGER_CLASS_PARAM = "hadoop.security.manager.class";
	
	private String _runMethod;
	private String _cancelMethod;
	private String _progressMethod;

	private Object _javaObject = null;
	
	
	
	private String userToProxy = null;
	private boolean shouldProxy = false;
	
	private HadoopSecurityManager hadoopSecurityManager;

	public HadoopJavaJob(String jobid, Props sysProps, Props jobProps, Logger log) throws RuntimeException {
		super(jobid, sysProps, jobProps, log);
		
		shouldProxy = getSysProps().getBoolean("azkaban.should.proxy");
		
		if(shouldProxy) {
			getLog().info("Initiating hadoop security manager.");
			try {
				hadoopSecurityManager = loadHadoopSecurityManager(sysProps, log);
			}
			catch(RuntimeException e) {
				e.printStackTrace();
				throw new RuntimeException("Failed to get hadoop security manager!" + e.getCause());
			}
		}
	}
	
	private HadoopSecurityManager loadHadoopSecurityManager(Props props, Logger logger) throws RuntimeException {

		Class<?> hadoopSecurityManagerClass = props.getClass(HADOOP_SECURITY_MANAGER_CLASS_PARAM, true, HadoopJavaJob.class.getClassLoader());
		getLog().info("Initializing hadoop security manager " + hadoopSecurityManagerClass.getName());
		HadoopSecurityManager hadoopSecurityManager = null;

		try {
			Method getInstanceMethod = hadoopSecurityManagerClass.getMethod("getInstance", Props.class);
			hadoopSecurityManager = (HadoopSecurityManager) getInstanceMethod.invoke(hadoopSecurityManagerClass, props);
		} 
		catch (InvocationTargetException e) {
			getLog().error("Could not instantiate Hadoop Security Manager "+ hadoopSecurityManagerClass.getName() + e.getCause());
			throw new RuntimeException(e.getCause());
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e.getCause());
		}

		return hadoopSecurityManager;
	}

	@Override
	protected String getJVMArguments() {
		String args = super.getJVMArguments();

		String typeUserJVMArgs = getJobProps().getString("jobtype.global.jvm.args", null);
		if (typeUserJVMArgs != null) {
			args += " " + typeUserJVMArgs;
		}
		String typeSysJVMArgs = getSysProps().getString("jobtype.global.jvm.args", null);
		if (typeSysJVMArgs != null) {
			args += " " + typeSysJVMArgs;
		}
		return args;
	}
	
	@Override
	protected List<String> getClassPaths() {
		List<String> classPath = super.getClassPaths();

		classPath.add(getSourcePathFromClass(HadoopJavaJobRunnerMain.class));
		classPath.add(getSourcePathFromClass(Props.class));
		classPath.add(getSourcePathFromClass(HadoopSecurityManager.class));
//		String loggerPath = getSourcePathFromClass(org.apache.log4j.Logger.class);
//		if (!classPath.contains(loggerPath)) {
//			classPath.add(loggerPath);
//		}

		// Add hadoop home to classpath
//		String hadoopHome = System.getenv("HADOOP_HOME");
//		if (hadoopHome == null) {
//			info("HADOOP_HOME not set, using default hadoop config.");
//		} else {
//			info("Using hadoop config found in " + hadoopHome);
//			classPath.add(new File(hadoopHome, "conf").getPath());
//		}
		
		List<String> typeClassPath = getSysProps().getStringList("jobtype.classpath", null, ",");
		if(typeClassPath != null) {
			// fill in this when load this jobtype
			String pluginDir = getSysProps().get("plugin.dir");
			for(String jar : typeClassPath) {
				File jarFile = new File(jar);
				if(!jarFile.isAbsolute()) {
					jarFile = new File(pluginDir + File.separatorChar + jar);
				}
				
				if(!classPath.contains(jarFile.getAbsoluteFile())) {
					classPath.add(jarFile.getAbsolutePath());
				}
			}
		}
		
		
		List<String> typeGlobalClassPath = getSysProps().getStringList("jobtype.global.classpath", null, ",");
		if(typeGlobalClassPath != null) {
			for(String jar : typeGlobalClassPath) {
				if(!classPath.contains(jar)) {
					classPath.add(jar);
				}
			}
		}

		return classPath;
	}

	@Override
	public void run() throws Exception {
		File f = null;
		if(shouldProxy) {
			userToProxy = getJobProps().getString("user.to.proxy");
			getLog().info("Need to proxy. Getting tokens.");
			f = getHadoopTokens(getJobProps());
		}
		try {
			super.run();
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}
		finally {
			if(f != null) {
				if(f.exists()) {
					f.delete();
				}
				cancelHadoopTokens(f);
			}
		}
	}
	
	private static String getSourcePathFromClass(Class<?> containedClass) {
		File file = new File(containedClass.getProtectionDomain().getCodeSource().getLocation().getPath());

		if (!file.isDirectory() && file.getName().endsWith(".class")) {
			String name = containedClass.getName();
			StringTokenizer tokenizer = new StringTokenizer(name, ".");
			while(tokenizer.hasMoreTokens()) {
				tokenizer.nextElement();

				file = file.getParentFile();
			}
			return file.getPath();  
		}
		else {
			return containedClass.getProtectionDomain().getCodeSource().getLocation().getPath();
		}
	}
	
	protected File getHadoopTokens(Props props) throws HadoopSecurityManagerException {

		File tokenFile = null;
		try {
			tokenFile = File.createTempFile("mr-azkaban", ".token");
		} catch (Exception e) {
			e.printStackTrace();
			throw new HadoopSecurityManagerException("Failed to create the token file.", e);
		}
		
		hadoopSecurityManager.prefetchToken(tokenFile, userToProxy, getLog());
		
		props.put("env."+UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION, tokenFile.getAbsolutePath());
		
		return tokenFile;
	}
	
	private void cancelHadoopTokens(File f) {
		try {
			hadoopSecurityManager.cancelTokens(f, userToProxy, getLog());
		} catch (HadoopSecurityManagerException e) {
			e.printStackTrace();
			getLog().error(e.getCause() + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			getLog().error(e.getCause() + e.getMessage());
		}
		
	}
	
	@Override
	protected String getJavaClass() {
		return HadoopJavaJobRunnerMain.class.getName();
	}

	@Override
	public String toString() {
		return "JavaJob{" + "_runMethod='" + _runMethod + '\''
				+ ", _cancelMethod='" + _cancelMethod + '\''
				+ ", _progressMethod='" + _progressMethod + '\''
				+ ", _javaObject=" + _javaObject + ", props="
				+ getJobProps() + '}';
	}
}

