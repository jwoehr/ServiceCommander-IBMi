package jesseg.ibmi.opensource;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jesseg.ibmi.opensource.SCException.FailureType;
import jesseg.ibmi.opensource.ServiceDefinition.BatchMode;
import jesseg.ibmi.opensource.ServiceDefinition.CheckAliveType;
import jesseg.ibmi.opensource.utils.QueryUtils;
import jesseg.ibmi.opensource.utils.AppLogger;
import jesseg.ibmi.opensource.utils.ProcessUtils;
import jesseg.ibmi.opensource.utils.SbmJobScript;
import jesseg.ibmi.opensource.utils.StringUtils;

/**
 * Where all the work happens
 * 
 * @author Jesse Gorzinski
 */
public class OperationExecutor {
    public enum Operation {
        START, STOP, RESTART, CHECK, INFO;
    }

    private final Operation m_op;
    private final String m_mainService;
    private final Map<String, ServiceDefinition> m_serviceDefs;
    private final AppLogger m_logger;

    public OperationExecutor(final Operation _op, final String _service, final Map<String, ServiceDefinition> serviceDefs, final AppLogger _logger) {
        m_op = _op;
        m_mainService = _service;
        m_serviceDefs = serviceDefs;
        m_logger = _logger;
    }

    private List<ServiceDefinition> findKnownDependents(final ServiceDefinition _service) {
        final List<ServiceDefinition> ret = new LinkedList<ServiceDefinition>();
        for (final ServiceDefinition entry : m_serviceDefs.values()) {
            for (final String entryDependency : entry.getDependencies()) {
                if (entryDependency.equalsIgnoreCase(_service.getName())) {
                    ret.add(entry);
                    continue;
                }
            }
        }
        return ret;
    }

    public File execute() throws SCException {
        final File logDir = AppDirectories.conf.getLogsDirectory();
        final String logFileName = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()) + "." + getMainService().getName() + ".log";
        final File logFile = new File(logDir.getAbsolutePath() + "/" + logFileName);
        try {
            switch (m_op) {
                case START:
                    startService(getMainService(), logFile);
                    return logFile;
                case STOP:
                    stopService(getMainService(), logFile);
                    return logFile;
                case CHECK:
                    printServiceStatus(getMainService(), m_logger);
                    return null;
                case INFO:
                    printInfo(getMainService(), m_logger);
                    return null;
                case RESTART:
                    stopService(getMainService(), logFile);
                    startService(getMainService(), logFile);
                    return logFile;
                default:
                    return null;
            }
        } catch (Exception e) {
            if (e instanceof SCException) {
                throw (SCException) e;
            }
            throw new SCException(m_logger, FailureType.GENERAL_ERROR, "A general error has occurred: %s", e.getLocalizedMessage());
        } finally {
            if (null != logFile) {
                if (logFile.exists()) {
                    if (0 >= logFile.length()) {
                        logFile.delete();
                        logFile.deleteOnExit();
                    } else {
                        m_logger.println("For details, see log file at: " + logFile.getAbsolutePath());
                    }
                }
            }
        }
    }

    private static void printInfo(final ServiceDefinition _svc, final AppLogger _logger) {
        _logger.println();
        _logger.println();
        _logger.println("---------------------------------------------------------------------");
        _logger.println(_svc.getName() + " (" + _svc.getFriendlyName() + ")");
        _logger.println();
        _logger.println();
        _logger.println("Defined in: " + _svc.getSource());
        _logger.println();
        _logger.println("Working Directory: " + _svc.getWorkingDirectory());
        _logger.println();
        _logger.println("Startup Command: " + _svc.getStartCommand());
        _logger.println("Startup Wait Time (s): " + _svc.getStartupWaitTime());
        _logger.println();
        final String shutdownCommand = _svc.getStopCommand();
        if (!StringUtils.isEmpty(shutdownCommand)) {
            _logger.println("Shutdown Command: " + shutdownCommand);
        }
        _logger.println("Shutdown Wait Time (s): " + _svc.getShutdownWaitTime());
        _logger.println();
        _logger.println("Check-alive type: " + _svc.getCheckAliveType().name());
        _logger.println("Check-alive condition: " + _svc.getCheckAliveCriteria());
        final BatchMode batchMode = _svc.getBatchMode();
        if (BatchMode.NO_BATCH == batchMode) {
            _logger.println("Batch Mode: <not running in batch>");
        } else {
            _logger.println("Batch Mode: <submitted to batch>");
            String batchJobName = _svc.getBatchJobName();
            if (StringUtils.isEmpty(batchJobName)) {
                batchJobName = "<default>";
            }
            _logger.println("    Batch Job Name: " + batchJobName);
            final String sbmjobOpts = _svc.getSbmJobOpts();
            if (!StringUtils.isEmpty(sbmjobOpts)) {
                _logger.println("    SBMJOB options: " + sbmjobOpts);
            }
        }
        final List<String> dependencies = _svc.getDependencies();
        if (!dependencies.isEmpty()) {
            _logger.println();
            _logger.println("Depends on the following services:");
            for (final String dependency : dependencies) {
                _logger.println("    " + dependency);
            }
        }
        _logger.println();
        _logger.println("Inherits environment variables?: " + _svc.isInheritingEnvironmentVars());
        final List<String> envVars = _svc.getEnvironmentVars();
        if (!envVars.isEmpty()) {
            _logger.println("Custom environment variables:");
            for (final String envVar : envVars) {
                _logger.println("    " + envVar);
            }
        }
        _logger.println("---------------------------------------------------------------------");
        _logger.println();
        _logger.println();
    }

    private static boolean printServiceStatus(final ServiceDefinition _svc, final AppLogger _logger) throws NumberFormatException, IOException, SCException {
        final boolean isRunning = isServiceRunning(_svc, _logger);
        _logger.printf("Service '%s' is %s\n", _svc.getFriendlyName(), (isRunning ? "RUNNING" : "NOT RUNNING"));// TODO: handle dependencies here?
        return isRunning;
    }

    private ServiceDefinition getMainService() throws SCException {
        // Get service definition for requested service
        final ServiceDefinition mainService = m_serviceDefs.get(m_mainService);

        if (null == mainService) {
            throw new SCException(m_logger, FailureType.MISSING_SERVICE_DEF, "Could not find definition for service '%s'", m_mainService);
        }
        return mainService;
    }

    private void stopService(final ServiceDefinition _svc, final File _logFile) throws IOException, InterruptedException, NumberFormatException, SCException {

        for (final ServiceDefinition dependentService : findKnownDependents(_svc)) {
            m_logger.printf("Attempting to stop dependent service '%s'...\n", dependentService.getFriendlyName());
            new OperationExecutor(Operation.STOP, dependentService.getName(), m_serviceDefs, m_logger).execute();
        }

        if (!isServiceRunning(_svc, m_logger)) {
            m_logger.printf("Service '%s' is already stopped\n", _svc.getFriendlyName());
            return;
        }
        final long startTime = new Date().getTime();
        final String command = _svc.getStopCommand();
        if (StringUtils.isEmpty(command)) {
            stopViaEndJob(_svc, _svc.getShutdownWaitTime(), m_logger);
        } else {
            final File directory = new File(_svc.getWorkingDirectory());

            final ArrayList<String> envp = new ArrayList<String>();
            if (_svc.isInheritingEnvironmentVars()) {
                for (final Entry<String, String> l : System.getenv().entrySet()) {
                    envp.add(l.getKey() + "=" + l.getValue());
                }
            }
            for (final String var : _svc.getEnvironmentVars()) {
                envp.add(var);
            }
            // _logger.println("envp of the child is " + envp.toString());
            final Process p = Runtime.getRuntime().exec("/QOpenSys/usr/bin/sh", envp.toArray(new String[0]), directory);
            final OutputStream stdin = p.getOutputStream();
            m_logger.println_verbose("running command: " + command);
            final String bashCommand = ("nohup " + command + " >> " + _logFile.getAbsolutePath() + " 2>&1 &");
            stdin.write(bashCommand.getBytes("UTF-8"));
            ProcessUtils.pipeStreams(_svc.getName(), p, m_logger);
            stdin.flush();
            stdin.close();
        }
        int secondsToWait = _svc.getShutdownWaitTime();
        boolean hasEndJobImmedBeenTried = false;
        while (true) {
            if (!isServiceRunning(_svc, m_logger)) {
                m_logger.printf("Service '%s' successfully stopped\n", _svc.getFriendlyName());
                return;
            }

            final long currentTime = new Date().getTime();
            if ((currentTime - startTime) > (1000 * secondsToWait)) {
                if (hasEndJobImmedBeenTried) {
                    throw new SCException(m_logger, FailureType.TIMEOUT_ON_SERVICE_STOP, "ERROR: Timed out waiting for service '%s' to stop. Giving up\n", _svc.getFriendlyName());
                } else {
                    m_logger.printf_err("ERROR: Timed out waiting for service '%s' to stop. Will try harder\n", _svc.getFriendlyName());
                    hasEndJobImmedBeenTried = true;
                    stopViaEndJob(_svc, 0, m_logger);
                    secondsToWait += 20;
                }
            }
            try {
                Thread.sleep(2500L);
            } catch (final InterruptedException e) {
                m_logger.exception(e);
            }
        }
    }

    private void startService(final ServiceDefinition _svc, final File _logFile) throws InterruptedException, IOException, SCException {

        for (final String dependencyName : _svc.getDependencies()) {
            final ServiceDefinition dependency = m_serviceDefs.get(dependencyName);
            if (null == dependency) {
                throw new SCException(m_logger, FailureType.INVALID_SERVICE_CONFIG, "ERROR: Service '%s' has unresolved dependency '%s'", _svc.getFriendlyName(), dependencyName);
            }
            try {
                m_logger.printf("Attempting to start service dependency '%s' (%s)...\n", dependencyName, dependency.getFriendlyName());
                new OperationExecutor(Operation.START, dependencyName, m_serviceDefs, m_logger).execute();
            } catch (Exception e) {
                throw new SCException(m_logger, FailureType.ERROR_STARTING_DEPENDENCY, "ERROR: Could not start dependency '%s' for service '%s': %s", dependencyName, _svc.getFriendlyName(), e.getLocalizedMessage());

            }
        }

        if (isServiceRunning(_svc, m_logger)) {
            m_logger.printf("Service '%s' is already running\n", _svc.getFriendlyName());
            return;
        }
        final String command = _svc.getStartCommand();
        final File directory = new File(_svc.getWorkingDirectory());

        final ArrayList<String> envp = new ArrayList<String>();
        if (_svc.isInheritingEnvironmentVars()) {
            for (final Entry<String, String> l : System.getenv().entrySet()) {
                envp.add(l.getKey() + "=" + l.getValue());
            }
        }
        for (final String var : _svc.getEnvironmentVars()) {
            envp.add(var);
        }

        final String bashCommand;
        if (BatchMode.NO_BATCH == _svc.getBatchMode()) {
            bashCommand = ("nohup " + command + " >> " + _logFile.getAbsolutePath() + " 2>&1 &");
        } else if (BatchMode.BATCH_QP2SHELL2 == _svc.getBatchMode()) {
            final String batchJobName = _svc.getBatchJobName();
            if (!StringUtils.isEmpty(batchJobName)) {
                m_logger.printfln_err_verbose("using custom batch job name");
                envp.add("SBMJOB_JOBNAME=" + batchJobName.trim()); // TODO: job name validation
            }
            final String sbmJobOpts = _svc.getSbmJobOpts();
            if (!StringUtils.isEmpty(sbmJobOpts)) {
                m_logger.printfln_err_verbose("using custom sbmJobOpts: " + sbmJobOpts);
                envp.add("SBMJOB_OPTS=" + sbmJobOpts.trim()); // TODO: job name validation
            }
            bashCommand = ("exec " + SbmJobScript.getQp2() + " " + command);
        } else {
            throw new SCException(m_logger, FailureType.UNSUPPORTED_OPERATION, "Unsupported operation has been requested");
        }
        m_logger.println_verbose("envp of the child is " + envp.toString());

        final Process p = Runtime.getRuntime().exec("/QOpenSys/usr/bin/sh", envp.toArray(new String[0]), directory);
        final long startTime = new Date().getTime();
        final OutputStream stdin = p.getOutputStream();
        m_logger.println_verbose("running command: " + command);

        stdin.write(bashCommand.getBytes("UTF-8"));
        ProcessUtils.pipeStreams(_svc.getName(), p, m_logger);
        stdin.flush();
        stdin.close();
        if (BatchMode.BATCH_QP2SHELL2 == _svc.getBatchMode()) {
            Thread.sleep(5000L); // Just to make sure the submitted job has some "sticking power"
        }
        final int secondsToWait = _svc.getStartupWaitTime();
        while (true) {
            if (isServiceRunning(_svc, m_logger)) {
                m_logger.printf("Service '%s' successfully started\n", _svc.getFriendlyName());
                return;
            }
            final long currentTime = new Date().getTime();
            if ((currentTime - startTime) > (1000 * secondsToWait)) {
                throw new SCException(m_logger, FailureType.TIMEOUT_ON_SERVICE_STARTUP, "ERROR: Timed out waiting for service '%s' to start\n", _svc.getFriendlyName());
            }
            try {
                Thread.sleep(1000L);
            } catch (final InterruptedException e) {
                m_logger.exception(e);
            }
        }
    }

    private static void stopViaEndJob(final ServiceDefinition _svc, final int _waitTime, final AppLogger _logger) throws IOException {
        if (CheckAliveType.PORT == _svc.getCheckAliveType()) {
            final List<String> jobs = QueryUtils.getListeningJobsByPort(_svc.getCheckAliveCriteria(), _logger);
            stopViaEndJob(jobs, _waitTime, _logger);
        } else if (CheckAliveType.JOBNAME == _svc.getCheckAliveType()) {
            _logger.println("Stopping via endjob");
            final List<String> jobs = QueryUtils.getJobs(_svc.getCheckAliveCriteria(), _logger);
            if (jobs.isEmpty()) {
                return;
            } else if (1 == jobs.size()) {
                stopViaEndJob(jobs, _waitTime, _logger);
            } else {
                _logger.println_err("ERROR: Multiple jobs found matching job name criteria!! Those jobs were: ");
                for (final String job : jobs) {
                    _logger.println_err("    " + job);
                }
                return;
            }
        }
    }

    private static void stopViaEndJob(final List<String> _jobs, final int _waitTime, final AppLogger _logger) throws IOException {
        final String optionString = (0 >= _waitTime) ? "OPTION(*IMMED)" : ("OPTION(*CNTRLD) DELAY(" + _waitTime + ")");
        for (final String job : _jobs) {
            _logger.println("Ending job " + job + " with " + optionString);
            final Process p = Runtime.getRuntime().exec(new String[] { "/QOpenSys/pkgs/bin/db2util", "-o", "space", "CALL QSYS2.QCMDEXC('ENDJOB JOB(" + job + ") " + optionString + "')" });
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                _logger.exception(e);
            }
        }
    }

    public static boolean isServiceRunning(final ServiceDefinition _svc, AppLogger _logger) throws SCException {
        final CheckAliveType checkType = _svc.getCheckAliveType();
        try {
            if (CheckAliveType.PORT == checkType) {
                return QueryUtils.isListeningOnPort(_svc.getCheckAliveCriteria(), _logger);
            } else if (CheckAliveType.JOBNAME == checkType) {
                return QueryUtils.isJobRunning(_svc.getCheckAliveCriteria(), _logger);
            }
        } catch (IOException ioe) {
            throw new SCException(_logger, FailureType.ERROR_CHECKING_STATUS, "Error occurred while checking status of service '%s': %s", _svc.getFriendlyName(), ioe.getLocalizedMessage());
        } catch (NumberFormatException nfe) {
            throw new SCException(_logger, FailureType.INVALID_SERVICE_CONFIG, "Invalid data for port number or job name criteria for service '%s': %s", _svc.getFriendlyName(), _svc.getCheckAliveCriteria());
        }
        throw new SCException(_logger, FailureType.UNSUPPORTED_OPERATION, "Unsupported operation has been requested");
    }
}
