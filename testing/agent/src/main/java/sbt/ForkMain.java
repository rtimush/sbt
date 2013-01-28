/* sbt -- Simple Build Tool
 * Copyright 2012 Eugene Vigdorchik
 */
package sbt;

import org.scalatools.testing.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class ForkMain {
	static class SubclassFingerscan implements TestFingerprint, Serializable {
		private boolean isModule;
		private String superClassName;
		SubclassFingerscan(SubclassFingerprint print) {
			isModule = print.isModule();
			superClassName = print.superClassName();
		}
		public boolean isModule() { return isModule; }
		public String superClassName() { return superClassName; }
	}
	static class AnnotatedFingerscan implements AnnotatedFingerprint, Serializable {
		private boolean isModule;
		private String annotationName;
		AnnotatedFingerscan(AnnotatedFingerprint print) {
			isModule = print.isModule();
			annotationName = print.annotationName();
		}
		public boolean isModule() { return isModule; }
		public String annotationName() { return annotationName; }
	}
	public static class ForkTestDefinition implements Serializable {
		public String name;
		public Fingerprint fingerprint;

		public ForkTestDefinition(String name, Fingerprint fingerprint) {
			this.name = name;
			if (fingerprint instanceof SubclassFingerprint) {
				this.fingerprint = new SubclassFingerscan((SubclassFingerprint) fingerprint);
			} else {
				this.fingerprint = new AnnotatedFingerscan((AnnotatedFingerprint) fingerprint);
			}
		}
	}
	static class ForkError extends Exception {
		private String originalMessage;
		private ForkError cause;
		ForkError(Throwable t) {
			originalMessage = t.getMessage();
			setStackTrace(t.getStackTrace());
			if (t.getCause() != null) cause = new ForkError(t.getCause());
		}
		public String getMessage() { return originalMessage; }
		public Exception getCause() { return cause; }
	}
	static class ForkEvent implements Event, Serializable {
		private String testName;
		private String description;
		private Result result;
		private Throwable error;
		ForkEvent(Event e) {
			testName = e.testName();
			description = e.description();
			result = e.result();
			if (e.error() != null) error = new ForkError(e.error());
		}
		public String testName() { return testName; }
		public String description() { return description; }
		public Result result() { return result; }
		public Throwable error() { return error; }
	}
	public static void main(String[] args) throws Exception {
		Socket socket = new Socket(InetAddress.getByName(null), Integer.valueOf(args[0]));
		final ObjectInputStream is = new ObjectInputStream(socket.getInputStream());
		final ObjectOutputStream os = new ObjectOutputStream(socket.getOutputStream());
		try {
			try {
				new Run().run(is, os);
			} finally {
				is.close();
				os.close();
			}
		} finally {
			System.exit(0);
		}
	}
	private static class Run {
		boolean matches(Fingerprint f1, Fingerprint f2) {
			if (f1 instanceof SubclassFingerprint && f2 instanceof SubclassFingerprint) {
				final SubclassFingerprint sf1 = (SubclassFingerprint) f1;
				final SubclassFingerprint sf2 = (SubclassFingerprint) f2;
				return sf1.isModule() == sf2.isModule() && sf1.superClassName().equals(sf2.superClassName());
			} else if (f1 instanceof AnnotatedFingerprint && f2 instanceof AnnotatedFingerprint) {
				AnnotatedFingerprint af1 = (AnnotatedFingerprint) f1;
				AnnotatedFingerprint af2 = (AnnotatedFingerprint) f2;
				return af1.isModule() == af2.isModule() && af1.annotationName().equals(af2.annotationName());
			}
			return false;
		}
		class RunAborted extends RuntimeException {
			RunAborted(Exception e) { super(e); }
		}
		void write(ObjectOutputStream os, Object obj) {
			try {
				os.writeObject(obj);
				os.flush();
			} catch (IOException e) {
				throw new RunAborted(e);
			}
		}
		void logError(ObjectOutputStream os, String message) {
			write(os, new Object[]{ForkTags.Error, message});
		}
		void writeEvents(ObjectOutputStream os, ForkTestDefinition test, ForkEvent[] events) {
			write(os, new Object[]{test.name, events});
		}
		void runTests(ObjectInputStream is, final ObjectOutputStream os) throws Exception {
			final boolean ansiCodesSupported = is.readBoolean();
			final ForkTestDefinition[] tests = (ForkTestDefinition[]) is.readObject();
			int nFrameworks = is.readInt();
			Logger[] loggers = {
				new Logger() {
					public boolean ansiCodesSupported() { return ansiCodesSupported; }
					public void error(String s) { logError(os, s); }
					public void warn(String s) { write(os, new Object[]{ForkTags.Warn, s}); }
					public void info(String s) { write(os, new Object[]{ForkTags.Info, s}); }
					public void debug(String s) { write(os, new Object[]{ForkTags.Debug, s}); }
					public void trace(Throwable t) { write(os, t); }
				}
			};

			for (int i = 0; i < nFrameworks; i++) {
				final String implClassName = (String) is.readObject();
				final String[] frameworkArgs = (String[]) is.readObject();

				final Framework framework;
				try {
					framework = (Framework) Class.forName(implClassName).newInstance();
				} catch (ClassNotFoundException e) {
					logError(os, "Framework implementation '" + implClassName + "' not present.");
					continue;
				}

				ArrayList<ForkTestDefinition> filteredTests = new ArrayList<ForkTestDefinition>();
				for (Fingerprint testFingerprint : framework.tests()) {
					for (ForkTestDefinition test : tests) {
						if (matches(testFingerprint, test.fingerprint)) filteredTests.add(test);
					}
				}
				final org.scalatools.testing.Runner runner = framework.testRunner(getClass().getClassLoader(), loggers);
				for (ForkTestDefinition test : filteredTests)
					runTestSafe(test, runner, framework, frameworkArgs, os);
			}
			write(os, ForkTags.Done);
			is.readObject();
		}
		void runTestSafe(ForkTestDefinition test, org.scalatools.testing.Runner runner, Framework framework, String[] frameworkArgs, ObjectOutputStream os) {
			ForkEvent[] events;
			try {
				events = runTest(test, runner, framework, frameworkArgs, os);
			} catch (Throwable t) {
				events = new ForkEvent[] { testError(os, test, "Uncaught exception when running " + test.name + ": " + t.toString(), t) };
			}
			writeEvents(os, test, events);
		}
		ForkEvent[] runTest(ForkTestDefinition test, org.scalatools.testing.Runner runner, Framework framework, String[] frameworkArgs, ObjectOutputStream os) {
			final List<ForkEvent> events = new ArrayList<ForkEvent>();
			EventHandler handler = new EventHandler() { public void handle(Event e){ events.add(new ForkEvent(e)); } };
			if (runner instanceof Runner2) {
				((Runner2) runner).run(test.name, test.fingerprint, handler, frameworkArgs);
			} else if (test.fingerprint instanceof TestFingerprint) {
				runner.run(test.name, (TestFingerprint) test.fingerprint, handler, frameworkArgs);
			} else {
				events.add(testError(os, test, "Framework '" + framework + "' does not support test '" + test.name + "'"));
			}
			return events.toArray(new ForkEvent[events.size()]);
		}
		void run(ObjectInputStream is, ObjectOutputStream os) throws Exception {
			try {
				runTests(is, os);
			} catch (RunAborted e) {
				internalError(e);
			} catch (Throwable t) {
				try {
					logError(os, "Uncaught exception when running tests: " + t.toString());
					write(os, t);
				} catch (Throwable t2) {
					internalError(t2);
				}
			}
		}
		void internalError(Throwable t) {
			System.err.println("Internal error when running tests: " + t.toString());
		}
		ForkEvent testEvent(final String name, final String desc, final Result r, final Throwable err) {
			return new ForkEvent(new Event() {
				public String testName() { return name; }
				public String description() { return desc; }
				public Result result() { return r; }
				public Throwable error() { return err; }
			});
		}
		ForkEvent testError(ObjectOutputStream os, ForkTestDefinition test, String message) {
			logError(os, message);
			return testEvent(test.name, message, Result.Error, null);
		}
		ForkEvent testError(ObjectOutputStream os, ForkTestDefinition test, String message, Throwable t) {
			logError(os, message);
			write(os, t);
			return testEvent(test.name, message, Result.Error, t);
		}
	}
}
