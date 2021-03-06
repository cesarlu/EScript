/*******************************************************************************
 * Copyright (c) 2011 Infineon Technologies Austria AG
 *
 * Contributors:
 *     Christian Pontesegger - initial version
 *     
 * Version Control:
 *     Last edited by: $Author: pontesegger $
 *     Date:           $Date: 2012-10-17 14:01:32 +0200 (Mi, 17 Okt 2012) $
 *     Revision:       $Revision: 1821 $
 *     Head URL:       $URL: https://grzw2b4ph2j.eu.infineon.com/svn/Eclipse_RCP/trunk/bundles/com.infineon.script/src/com/infineon/script/AbstractScriptEngine.java $
 *******************************************************************************/

package com.codeandme.scripting;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.codeandme.scripting.legacy.BreakException;
import com.codeandme.scripting.legacy.ScriptService;

/**
 * Base implementation for a script engine. Handles Job implementation of script engine, adding script code for execution, module loading support and a basic
 * online help system.
 */
public abstract class AbstractScriptEngine extends Job implements IScriptEngine {

    /** List of code junks to be executed. */
    private final List<Script> mCodePieces = Collections.synchronizedList(new ArrayList<Script>());

    private final ListenerList mExecutionListeners = new ListenerList();

    /** Indicator to terminate once this Job gets IDLE. */
    private boolean mTerminateOnIdle = true;

    private PrintStream mOutStream = null;
    private PrintStream mErrorStream = null;
    private InputStream mInStream = null;

    private FileTrace mFileTrace = new FileTrace();

    private String mID;

    /**
     * Constructor. Contains a name for the underlying job and an indicator for the presence of online help.
     * 
     * @param name
     *            name of script engine job
     * @param helpAvailable
     *            <code>true</code> when help code shall be generated on the fly
     */
    public AbstractScriptEngine(final String name) {
        super(name);

        // make this a system job (not visible to the user)
        setSystem(true);
    }

    @Override
    public final void setEngineID(final String ID) {
        mID = ID;
    }

    @Override
    public String getID() {
        return mID;
    }

    @Override
    public final ScriptResult executeAsync(final Object content) {
        final Script piece = new Script(content);

        mCodePieces.add(piece);
        synchronized (this) {
            notifyAll();
        }

        return piece.getResult();
    }

    @Override
    public final ScriptResult executeSync(final Object content) throws InterruptedException {

        if (getState() == NONE)
            throw new RuntimeException("Engine is not started yet, cannot run code");

        final ScriptResult result = executeAsync(content);
        synchronized (result) {
            while (!result.isReady())
                result.wait();
        }

        return result;
    }

    @Override
    public final ScriptResult inject(final Object content) {
        final Script script = new Script(content);

        // injected code shall not trigger a new event, therefore notifyListerners needs to be false
        return inject(script, false);
    }

    /**
     * Inject script code to the script engine. Injected code is processed synchronous by the current thread. Therefore this is a blocking call.
     * 
     * @param script
     *            script to be executed
     * @return script execution result
     */
    private ScriptResult inject(final Script script, final boolean notifyListeners) {

        synchronized (script.getResult()) {

            try {
                mFileTrace.push(script.getFile());

                // execution
                if (notifyListeners)
                    notifyExecutionListeners(script, IExecutionListener.SCRIPT_START);

                final InputStream code = script.getCode();
                if (code != null) {
                    script.setResult(execute(code, script.getFile(), mFileTrace.peek().getFileName()));
                } else
                    script.setException(new Exception("Invalid script input detected"));

            } catch (final ExitException e) {
                script.setResult(e.getCondition());

            } catch (final BreakException e) {
                script.setResult(e.getCondition());

            } catch (final Exception e) {
                script.setException(e);
                getErrorStream().println(e.getLocalizedMessage());

            } finally {
                if (notifyListeners)
                    notifyExecutionListeners(script, IExecutionListener.SCRIPT_END);

                mFileTrace.pop();
            }
        }

        return script.getResult();
    }

    @Override
    protected final IStatus run(final IProgressMonitor monitor) {
        if (setupEngine()) {
            mFileTrace = new FileTrace();

            notifyExecutionListeners(null, IExecutionListener.ENGINE_START);

            // main loop
            while ((!monitor.isCanceled()) && (!isTerminated())) {

                // execute code
                if (!mCodePieces.isEmpty()) {
                    final Script piece = mCodePieces.remove(0);
                    inject(piece, true);

                } else {

                    synchronized (this) {
                        if (!isTerminated()) {
                            try {
                                wait();
                            } catch (final InterruptedException e) {
                            }
                        }
                    }
                }
            }

            // discard pending code pieces
            synchronized (mCodePieces) {
                for (final Script script : mCodePieces)
                    script.setException(new ExitException());
            }

            mCodePieces.clear();

            notifyExecutionListeners(null, IExecutionListener.ENGINE_END);

            teardownEngine();
        }

        if (isTerminated())
            return Status.OK_STATUS;

        return Status.CANCEL_STATUS;
    }

    @Override
    public PrintStream getOutputStream() {
        return (mOutStream != null) ? mOutStream : System.out;
    }

    @Override
    public void setOutputStream(final PrintStream outputStream) {
        mOutStream = outputStream;
    }

    @Override
    public InputStream getInputStream() {
        return (mInStream != null) ? mInStream : System.in;
    }

    @Override
    public void setInputStream(final InputStream inputStream) {
        mInStream = inputStream;
    }

    @Override
    public PrintStream getErrorStream() {
        return (mErrorStream != null) ? mErrorStream : System.err;
    }

    @Override
    public void setErrorStream(final PrintStream errorStream) {
        mErrorStream = errorStream;
    }

    @Override
    public final void setTerminateOnIdle(final boolean terminate) {
        mTerminateOnIdle = terminate;
    }

    /**
     * Get termination status of the interpreter. A terminated interpreter cannot be restarted.
     * 
     * @return true if interpreter is terminated.
     */
    private boolean isTerminated() {
        return mTerminateOnIdle && mCodePieces.isEmpty();
    }

    /**
     * Get idle status of the interpreter. The interpreter is IDLE if there are no pending execution requests and the interpreter is not terminated.
     * 
     * @return true if interpreter is IDLE
     */
    @Override
    public boolean isIdle() {
        return mCodePieces.isEmpty();
    }

    @Override
    public void addExecutionListener(final IExecutionListener listener) {
        mExecutionListeners.add(listener);
    }

    @Override
    public void removeExecutionListener(final IExecutionListener listener) {
        mExecutionListeners.remove(listener);
    }

    protected void notifyExecutionListeners(final Script script, final int status) {
        for (final Object listener : mExecutionListeners.getListeners())
            ((IExecutionListener) listener).notify(this, script, status);
    }

    @Override
    public void terminate() {
        setTerminateOnIdle(true);
        mCodePieces.clear();
        terminateCurrent();

        // ask thread to terminate
        cancel();
        if (getThread() != null)
            getThread().interrupt();
    }

    @Override
    public void reset() {
        // make sure that everybody gets notified that script engine got a reset
        for (final Script script : mCodePieces)
            script.setException(new ExitException("Script engine got resetted."));

        mCodePieces.clear();

        // re-enable launch extensions to register themselves
        for (final IScriptEngineLaunchExtension extension : ScriptService.getLaunchExtensions())
            extension.createEngine(this);
    }

    @Override
    public FileTrace getFileTrace() {
        return mFileTrace;
    }

    /**
     * Setup method for script engine. Run directly after the engine is activated. Needs to return <code>true</code>. Otherwise the engine will terminate
     * instantly.
     * 
     * @return <code>true</code> when setup succeeds
     */
    protected abstract boolean setupEngine();

    /**
     * Teatdown engine. Called immediately before the engine terminates. This method is not called when {@link #setupEngine()} fails.
     * 
     * @return teardown result
     */
    protected abstract boolean teardownEngine();

    /**
     * Execute script code.
     * 
     * @param fileName
     *            name of file executed
     * @param reader
     *            reader for script data to be executed
     * @return execution result
     * @throws Exception
     *             any exception thrown during script execution
     */
    protected abstract Object execute(InputStream code, Object reference, String fileName) throws Exception;
}
