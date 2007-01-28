/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Portions Copyright 2006-2007 Sun Microsystems, Inc.
 */
package org.opends.server.backends.task;



import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import org.opends.server.core.DirectoryServer;
import org.opends.server.protocols.asn1.ASN1OctetString;
import org.opends.server.types.Attribute;
import org.opends.server.types.AttributeType;
import org.opends.server.types.AttributeValue;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.DN;
import org.opends.server.types.Entry;
import org.opends.server.types.ErrorLogCategory;
import org.opends.server.types.ErrorLogSeverity;
import org.opends.server.types.InitializationException;
import org.opends.server.util.TimeThread;

import static org.opends.server.config.ConfigConstants.*;
import static org.opends.server.loggers.Debug.*;
import static org.opends.server.loggers.Error.*;
import static org.opends.server.messages.BackendMessages.*;
import static org.opends.server.messages.MessageHandler.*;
import org.opends.server.messages.MessageHandler;
import static org.opends.server.util.ServerConstants.*;
import static org.opends.server.util.StaticUtils.*;



/**
 * This class defines a task that may be executed by the task backend within the
 * Directory Server.
 */
public abstract class Task
       implements Comparable<Task>
{
  /**
   * The fully-qualified name of this class for debugging purposes.
   */
  private static final String CLASS_NAME =
       "org.opends.server.backends.task.Task";



  // The DN for the task entry.
  private DN taskEntryDN;

  // The entry that actually defines this task.
  private Entry taskEntry;

  // The action to take if one of the dependencies for this task does not
  // complete successfully.
  private FailedDependencyAction failedDependencyAction;

  // The counter used for log messages associated with this task.
  private int logMessageCounter;

  // The task IDs of other tasks on which this task is dependent.
  private LinkedList<String> dependencyIDs;

  // A set of log messages generated by this task.
  private LinkedList<String> logMessages;

  // The set of e-mail addresses of the users to notify when the task is done
  // running, regardless of whether it completes successfully.
  private LinkedList<String> notifyOnCompletion;

  // The set of e-mail addresses of the users to notify if the task does not
  // complete successfully for some reason.
  private LinkedList<String> notifyOnError;

  // The time that processing actually started for this task.
  private long actualStartTime;

  // The time that actual processing ended for this task.
  private long completionTime;

  // The time that this task was scheduled to start processing.
  private long scheduledStartTime;

  // The ID of the recurring task with which this task is associated.
  private String recurringTaskID;

  // The unique ID assigned to this task.
  private String taskID;

  // The current state of this task.
  private TaskState taskState;

  // The scheduler with which this task is associated.
  private TaskScheduler taskScheduler;



  /**
   * Performs generic initialization for this task based on the information in
   * the provided task entry.
   *
   * @param  taskScheduler  The scheduler with which this task is associated.
   * @param  taskEntry      The entry containing the task configuration.
   *
   * @throws  InitializationException  If a problem occurs while performing the
   *                                   initialization.
   */
  public final void initializeTaskInternal(TaskScheduler taskScheduler,
                                           Entry taskEntry)
         throws InitializationException
  {
    assert debugEnter(CLASS_NAME, "initializeTaskInternal",
                      String.valueOf(taskEntry));

    this.taskScheduler = taskScheduler;
    this.taskEntry     = taskEntry;
    this.taskEntryDN   = taskEntry.getDN();

    String taskDN = taskEntryDN.toString();

    logMessageCounter = 0;


    // Get the task ID and recurring task ID values.  At least one of them must
    // be provided.  If it's a recurring task and there is no task ID, then
    // generate one on the fly.
    taskID          = getAttributeValue(ATTR_TASK_ID, false);
    recurringTaskID = getAttributeValue(ATTR_RECURRING_TASK_ID, false);
    if (taskID == null)
    {
      if (recurringTaskID == null)
      {
        int    msgID   = MSGID_TASK_MISSING_ATTR;
        String message = getMessage(msgID, String.valueOf(taskEntry.getDN()),
                                    ATTR_TASK_ID);
        throw new InitializationException(msgID, message);
      }
      else
      {
        taskID = UUID.randomUUID().toString();
      }
    }


    // Get the current state from the task.  If there is none, then assume it's
    // a new task.
    String stateString = getAttributeValue(ATTR_TASK_STATE, false);
    if (stateString == null)
    {
      taskState = TaskState.UNSCHEDULED;
    }
    else
    {
      taskState = TaskState.fromString(stateString);
      if (taskState == null)
      {
        int    msgID   = MSGID_TASK_INVALID_STATE;
        String message = getMessage(msgID, taskDN, stateString);
        throw new InitializationException(msgID, message);
      }
    }


    // Get the scheduled start time for the task, if there is one.  It may be
    // in either UTC time (a date followed by a 'Z') or in the local time zone
    // (not followed by a 'Z').
    scheduledStartTime = -1;
    String timeString = getAttributeValue(ATTR_TASK_SCHEDULED_START_TIME,
                                          false);
    if (timeString != null)
    {
      SimpleDateFormat dateFormat;
      if (timeString.endsWith("Z"))
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_UTC_TIME);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      }
      else
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_COMPACT_LOCAL_TIME);
      }

      try
      {
        scheduledStartTime = dateFormat.parse(timeString).getTime();
      }
      catch (Exception e)
      {
        assert debugException(CLASS_NAME, "initializeTaskInternal", e);

        int    msgID   = MSGID_TASK_CANNOT_PARSE_SCHEDULED_START_TIME;
        String message = getMessage(msgID, timeString, taskDN);
        throw new InitializationException(msgID, message, e);
      }
    }


    // Get the actual start time for the task, if there is one.
    actualStartTime = -1;
    timeString = getAttributeValue(ATTR_TASK_ACTUAL_START_TIME, false);
    if (timeString != null)
    {
      SimpleDateFormat dateFormat;
      if (timeString.endsWith("Z"))
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_UTC_TIME);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      }
      else
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_COMPACT_LOCAL_TIME);
      }

      try
      {
        actualStartTime = dateFormat.parse(timeString).getTime();
      }
      catch (Exception e)
      {
        assert debugException(CLASS_NAME, "initializeTaskInternal", e);

        int    msgID   = MSGID_TASK_CANNOT_PARSE_ACTUAL_START_TIME;
        String message = getMessage(msgID, timeString, taskDN);
        throw new InitializationException(msgID, message, e);
      }
    }


    // Get the completion time for the task, if there is one.
    completionTime = -1;
    timeString = getAttributeValue(ATTR_TASK_COMPLETION_TIME, false);
    if (timeString != null)
    {
      SimpleDateFormat dateFormat;
      if (timeString.endsWith("Z"))
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_UTC_TIME);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      }
      else
      {
        dateFormat = new SimpleDateFormat(DATE_FORMAT_COMPACT_LOCAL_TIME);
      }

      try
      {
        completionTime = dateFormat.parse(timeString).getTime();
      }
      catch (Exception e)
      {
        assert debugException(CLASS_NAME, "initializeTaskInternal", e);

        int    msgID   = MSGID_TASK_CANNOT_PARSE_COMPLETION_TIME;
        String message = getMessage(msgID, timeString, taskDN);
        throw new InitializationException(msgID, message, e);
      }
    }


    // Get information about any dependencies that the task might have.
    dependencyIDs = getAttributeValues(ATTR_TASK_DEPENDENCY_IDS);

    failedDependencyAction = FailedDependencyAction.CANCEL;
    String actionString = getAttributeValue(ATTR_TASK_FAILED_DEPENDENCY_ACTION,
                                            false);
    if (actionString != null)
    {
      failedDependencyAction = FailedDependencyAction.fromString(actionString);
      if (failedDependencyAction == null)
      {
        failedDependencyAction = FailedDependencyAction.CANCEL;
      }
    }


    // Get the information about the e-mail addresses to use for notification
    // purposes.
    notifyOnCompletion = getAttributeValues(ATTR_TASK_NOTIFY_ON_COMPLETION);
    notifyOnError      = getAttributeValues(ATTR_TASK_NOTIFY_ON_ERROR);


    // Get the log messages for the task.
    logMessages  = getAttributeValues(ATTR_TASK_LOG_MESSAGES);
  }



  /**
   * Retrieves the single value for the requested attribute as a string.
   *
   * @param  attributeName  The name of the attribute for which to retrieve the
   *                        value.
   * @param  isRequired     Indicates whether the attribute is required to have
   *                        a value.
   *
   * @return  The value for the requested attribute, or <CODE>null</CODE> if it
   *          is not present in the entry and is not required.
   *
   * @throws  InitializationException  If the requested attribute is not present
   *                                   in the entry but is required, or if there
   *                                   are multiple instances of the requested
   *                                   attribute in the entry with different
   *                                   sets of options, or if there are multiple
   *                                   values for the requested attribute.
   */
  private String getAttributeValue(String attributeName, boolean isRequired)
          throws InitializationException
  {
    assert debugEnter(CLASS_NAME, "getAttributeValue",
                      String.valueOf(attributeName),
                      String.valueOf(isRequired));

    List<Attribute> attrList =
         taskEntry.getAttribute(attributeName.toLowerCase());
    if ((attrList == null) || attrList.isEmpty())
    {
      if (isRequired)
      {
        int    msgID   = MSGID_TASK_MISSING_ATTR;
        String message = getMessage(msgID, String.valueOf(taskEntry.getDN()),
                                    attributeName);
        throw new InitializationException(msgID, message);
      }
      else
      {
        return null;
      }
    }

    if (attrList.size() > 1)
    {
      int    msgID   = MSGID_TASK_MULTIPLE_ATTRS_FOR_TYPE;
      String message = getMessage(msgID, attributeName,
                                  String.valueOf(taskEntry.getDN()));
      throw new InitializationException(msgID, message);
    }

    Iterator<AttributeValue> iterator = attrList.get(0).getValues().iterator();
    if (! iterator.hasNext())
    {
      if (isRequired)
      {
        int    msgID   = MSGID_TASK_NO_VALUES_FOR_ATTR;
        String message = getMessage(msgID, attributeName,
                                    String.valueOf(taskEntry.getDN()));
        throw new InitializationException(msgID, message);
      }
      else
      {
        return null;
      }
    }

    AttributeValue value = iterator.next();
    if (iterator.hasNext())
    {
      int    msgID   = MSGID_TASK_MULTIPLE_VALUES_FOR_ATTR;
      String message = getMessage(msgID, attributeName,
                                  String.valueOf(taskEntry.getDN()));
      throw new InitializationException(msgID, message);
    }

    return value.getStringValue();
  }



  /**
   * Retrieves the values for the requested attribute as a list of strings.
   *
   * @param  attributeName  The name of the attribute for which to retrieve the
   *                        values.
   *
   * @return  The list of values for the requested attribute, or an empty list
   *          if the attribute does not exist or does not have any values.
   *
   * @throws  InitializationException  If there are multiple instances of the
   *                                   requested attribute in the entry with
   *                                   different sets of options.
   */
  private LinkedList<String> getAttributeValues(String attributeName)
          throws InitializationException
  {
    assert debugEnter(CLASS_NAME, "getAttributeValues",
                      String.valueOf(attributeName));

    LinkedList<String> valueStrings = new LinkedList<String>();

    List<Attribute> attrList =
         taskEntry.getAttribute(attributeName.toLowerCase());
    if ((attrList == null) || attrList.isEmpty())
    {
      return valueStrings;
    }

    if (attrList.size() > 1)
    {
      int    msgID   = MSGID_TASK_MULTIPLE_ATTRS_FOR_TYPE;
      String message = getMessage(msgID, attributeName);
      throw new InitializationException(msgID, message);
    }

    Iterator<AttributeValue> iterator = attrList.get(0).getValues().iterator();
    while (iterator.hasNext())
    {
      valueStrings.add(iterator.next().getStringValue());
    }

    return valueStrings;
  }



  /**
   * Retrieves the DN of the entry containing the definition for this task.
   *
   * @return  The DN of the entry containing the definition for this task.
   */
  public final DN getTaskEntryDN()
  {
    assert debugEnter(CLASS_NAME, "getTaskEntryDN");

    return taskEntryDN;
  }



  /**
   * Retrieves the entry containing the definition for this task.
   *
   * @return  The entry containing the definition for this task.
   */
  public final Entry getTaskEntry()
  {
    assert debugEnter(CLASS_NAME, "getTaskEntry");

    return taskEntry;
  }



  /**
   * Retrieves the unique identifier assigned to this task.
   *
   * @return  The unique identifier assigned to this task.
   */
  public final String getTaskID()
  {
    assert debugEnter(CLASS_NAME, "getTaskID");

    return taskID;
  }



  /**
   * Retrieves the unique identifier assigned to the recurring task that is
   * associated with this task, if there is one.
   *
   * @return  The unique identifier assigned to the recurring task that is
   *          associated with this task, or <CODE>null</CODE> if it is not
   *          associated with any recurring task.
   */
  public final String getRecurringTaskID()
  {
    assert debugEnter(CLASS_NAME, "getRecurringTaskID");

    return recurringTaskID;
  }



  /**
   * Retrieves the current state for this task.
   *
   * @return  The current state for this task.
   */
  public final TaskState getTaskState()
  {
    assert debugEnter(CLASS_NAME, "getTaskState");

    return taskState;
  }



  /**
   * Sets the state for this task and updates the associated task entry as
   * necessary.  It does not automatically persist the updated task information
   * to disk.
   *
   * @param  taskState  The new state to use for the task.
   */
  void setTaskState(TaskState taskState)
  {
    assert debugEnter(CLASS_NAME, "setTaskState", String.valueOf(taskState));

    Lock lock = taskScheduler.writeLockEntry(taskEntryDN);

    try
    {
      this.taskState = taskState;

      AttributeType type =
           DirectoryServer.getAttributeType(ATTR_TASK_STATE.toLowerCase());
      if (type == null)
      {
        type = DirectoryServer.getDefaultAttributeType(ATTR_TASK_STATE);
      }

      LinkedHashSet<AttributeValue> values =
           new LinkedHashSet<AttributeValue>();
      values.add(new AttributeValue(type,
                                    new ASN1OctetString(taskState.toString())));

      ArrayList<Attribute> attrList = new ArrayList<Attribute>(1);
      attrList.add(new Attribute(type, ATTR_TASK_STATE, values));
      taskEntry.putAttribute(type, attrList);
    }
    finally
    {
      taskScheduler.unlockEntry(taskEntryDN, lock);
    }
  }



  /**
   * Retrieves the scheduled start time for this task, if there is one.  The
   * value returned will be in the same format as the return value for
   * <CODE>System.currentTimeMillis()</CODE>.  Any value representing a time in
   * the past, or any negative value, should be taken to mean that the task
   * should be considered eligible for immediate execution.
   *
   * @return  The scheduled start time for this task.
   */
  public final long getScheduledStartTime()
  {
    assert debugEnter(CLASS_NAME, "getStartTime");

    return scheduledStartTime;
  }



  /**
   * Retrieves the time that this task actually started running, if it has
   * started.  The value returned will be in the same format as the return value
   * for <CODE>System.currentTimeMillis()</CODE>.
   *
   * @return  The time that this task actually started running, or -1 if it has
   *          not yet been started.
   */
  public final long getActualStartTime()
  {
    assert debugEnter(CLASS_NAME, "getActualStartTime");

    return actualStartTime;
  }



  /**
   * Sets the actual start time for this task and updates the associated task
   * entry as necessary.  It does not automatically persist the updated task
   * information to disk.
   *
   * @param  actualStartTime  The actual start time to use for this task.
   */
  private void setActualStartTime(long actualStartTime)
  {
    assert debugEnter(CLASS_NAME, "setActualStartTime",
                      String.valueOf(actualStartTime));

    Lock lock = taskScheduler.writeLockEntry(taskEntryDN);

    try
    {
      this.actualStartTime = actualStartTime;

      AttributeType type = DirectoryServer.getAttributeType(
                                ATTR_TASK_ACTUAL_START_TIME.toLowerCase());
      if (type == null)
      {
        type = DirectoryServer.getDefaultAttributeType(
                    ATTR_TASK_ACTUAL_START_TIME);
      }

      SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT_UTC_TIME);
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      Date d = new Date(actualStartTime);
      ASN1OctetString s = new ASN1OctetString(dateFormat.format(d));

      LinkedHashSet<AttributeValue> values =
           new LinkedHashSet<AttributeValue>();
      values.add(new AttributeValue(type, s));

      ArrayList<Attribute> attrList = new ArrayList<Attribute>(1);
      attrList.add(new Attribute(type, ATTR_TASK_ACTUAL_START_TIME, values));
      taskEntry.putAttribute(type, attrList);
    }
    finally
    {
      taskScheduler.unlockEntry(taskEntryDN, lock);
    }
  }



  /**
   * Retrieves the time that this task completed all of its associated
   * processing (regardless of whether it was successful), if it has completed.
   * The value returned will be in the same format as the return value for
   * <CODE>System.currentTimeMillis()</CODE>.
   *
   * @return  The time that this task actually completed running, or -1 if it
   *          has not yet completed.
   */
  public final long getCompletionTime()
  {
    assert debugEnter(CLASS_NAME, "getCompletionTime");

    return completionTime;
  }



  /**
   * Sets the completion time for this task and updates the associated task
   * entry as necessary.  It does not automatically persist the updated task
   * information to disk.
   *
   * @param  completionTime  The completion time to use for this task.
   */
  private void setCompletionTime(long completionTime)
  {
    assert debugEnter(CLASS_NAME, "setCompletionTime",
                      String.valueOf(completionTime));

    Lock lock = taskScheduler.writeLockEntry(taskEntryDN);

    try
    {
      this.completionTime = completionTime;

      AttributeType type = DirectoryServer.getAttributeType(
                                ATTR_TASK_COMPLETION_TIME.toLowerCase());
      if (type == null)
      {
        type =
             DirectoryServer.getDefaultAttributeType(ATTR_TASK_COMPLETION_TIME);
      }

      SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT_UTC_TIME);
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      Date d = new Date(completionTime);
      ASN1OctetString s = new ASN1OctetString(dateFormat.format(d));

      LinkedHashSet<AttributeValue> values =
           new LinkedHashSet<AttributeValue>();
      values.add(new AttributeValue(type, s));

      ArrayList<Attribute> attrList = new ArrayList<Attribute>(1);
      attrList.add(new Attribute(type, ATTR_TASK_COMPLETION_TIME, values));
      taskEntry.putAttribute(type, attrList);
    }
    finally
    {
      taskScheduler.unlockEntry(taskEntryDN, lock);
    }
  }



  /**
   * Retrieves the set of task IDs for any tasks on which this task is
   * dependent.  This list must not be directly modified by the caller.
   *
   * @return  The set of task IDs for any tasks on which this task is dependent.
   */
  public final LinkedList<String> getDependencyIDs()
  {
    assert debugEnter(CLASS_NAME, "getDependencyIDs");

    return dependencyIDs;
  }



  /**
   * Retrieves the action that should be taken if any of the dependencies for
   * this task do not complete successfully.
   *
   * @return  The action that should be taken if any of the dependencies for
   *          this task do not complete successfully.
   */
  public final FailedDependencyAction getFailedDependencyAction()
  {
    assert debugEnter(CLASS_NAME, "getFailedDependencyAction");

    return failedDependencyAction;
  }



  /**
   * Retrieves the set of e-mail addresses for the users that should receive a
   * notification message when processing for this task has completed.  This
   * notification will be sent to these users regardless of whether the task
   * completed successfully.  This list must not be directly modified by the
   * caller.
   *
   * @return  The set of e-mail addresses for the users that should receive a
   *          notification message when processing for this task has
   *          completed.
   */
  public final LinkedList<String> getNotifyOnCompletionAddresses()
  {
    assert debugEnter(CLASS_NAME, "getNotifyOnCompletionAddresses");

    return notifyOnCompletion;
  }



  /**
   * Retrieves the set of e-mail addresses for the users that should receive a
   * notification message if processing for this task does not complete
   * successfully.  This list must not be directly modified by the caller.
   *
   * @return  The set of e-mail addresses for the users that should receive a
   *          notification message if processing for this task does not complete
   *          successfully.
   */
  public final LinkedList<String> getNotifyOnErrorAddresses()
  {
    assert debugEnter(CLASS_NAME, "getNotifyOnErrorAddresses");

    return notifyOnError;
  }



  /**
   * Retrieves the set of messages that were logged by this task.  This list
   * must not be directly modified by the caller.
   *
   * @return  The set of messages that were logged by this task.
   */
  public final LinkedList<String> getLogMessages()
  {
    assert debugEnter(CLASS_NAME, "getLogMessages");

    return logMessages;
  }

  /**
   * Writes a message to the error log using the provided information.
   * Tasks should use this method to log messages to the error log instead of
   * the one in <code>org.opends.server.loggers.Error</code> to ensure the
   * messages are included in the ds-task-log-message attribute.
   *
   * @param  category  The category that may be used to determine whether to
   *                   actually log this message.
   * @param  severity  The severity that may be used to determine whether to
   *                   actually log this message.
   * @param  errorID   The error ID that uniquely identifies the provided format
   *                   string.
   */
  protected void logError(ErrorLogCategory category,
                              ErrorLogSeverity severity, int errorID)
  {
    String message = MessageHandler.getMessage(errorID);

    addLogMessage(severity, errorID, message);
    org.opends.server.loggers.Error.logError(category, severity, errorID);
  }



  /**
   * Writes a message to the error log using the provided information.
   * Tasks should use this method to log messages to the error log instead of
   * the one in <code>org.opends.server.loggers.Error</code> to ensure the
   * messages are included in the ds-task-log-message attribute.
   *
   * @param  category  The category that may be used to determine whether to
   *                   actually log this message.
   * @param  severity  The severity that may be used to determine whether to
   *                   actually log this message.
   * @param  errorID   The error ID that uniquely identifies the provided format
   *                   string.
   * @param  args      The set of arguments to use for the provided format
   *                   string.
   */
  protected void logError(ErrorLogCategory category,
                              ErrorLogSeverity severity, int errorID,
                              Object... args)
  {
    String message = MessageHandler.getMessage(errorID);

    addLogMessage(severity, errorID, message);
    org.opends.server.loggers.Error.logError(category, severity, errorID, args);
  }



  /**
   * Writes a message to the error log using the provided information.
   * Tasks should use this method to log messages to the error log instead of
   * the one in <code>org.opends.server.loggers.Error</code> to ensure the
   * messages are included in the ds-task-log-message attribute.
   *
   * @param  category  The category that may be used to determine whether to
   *                   actually log this message.
   * @param  severity  The severity that may be used to determine whether to
   *                   actually log this message.
   * @param  message   The message to be logged.
   * @param  errorID   The error ID that uniquely identifies the format string
   *                   used to generate the provided message.
   */
  protected void logError(ErrorLogCategory category,
                              ErrorLogSeverity severity, String message,
                              int errorID)
  {
    addLogMessage(severity, errorID, message);
    org.opends.server.loggers.Error.logError(category, severity, message,
        errorID);
  }

  /**
   * Adds a log message to the set of messages logged by this task.  This method
   * should not be called directly by tasks, but rather will be called
   * indirectly through the logError methods in this class. It does not
   * automatically persist the updated task information to disk.
   *
   * @param  severity       The severity level for the log message.
   * @param  messageID      The ID that uniquely identifies the log message.
   * @param  messageString  The text of the log message
   */
  void addLogMessage(ErrorLogSeverity severity, int messageID,
                     String messageString)
  {
    assert debugEnter(CLASS_NAME, "addLogMessage",
                      String.valueOf(severity), String.valueOf(messageID),
                      String.valueOf(messageString));

    Lock lock = taskScheduler.writeLockEntry(taskEntryDN);

    try
    {
      StringBuilder buffer = new StringBuilder();
      buffer.append("[");
      buffer.append(TimeThread.getLocalTime());
      buffer.append("] severity=\"");
      buffer.append(severity.getSeverityName());
      buffer.append("\" msgCount=");
      buffer.append(logMessageCounter++);
      buffer.append(" msgID=");
      buffer.append(messageID);
      buffer.append(" message=\"");
      buffer.append(messageString);
      buffer.append("\"");

      String message = buffer.toString();
      logMessages.add(message);


      AttributeType type = DirectoryServer.getAttributeType(
                                ATTR_TASK_LOG_MESSAGES.toLowerCase());
      if (type == null)
      {
        type = DirectoryServer.getDefaultAttributeType(ATTR_TASK_LOG_MESSAGES);
      }

      List<Attribute> attrList = taskEntry.getAttribute(type);
      if (attrList == null)
      {
        attrList = new ArrayList<Attribute>();

        LinkedHashSet<AttributeValue> values =
             new LinkedHashSet<AttributeValue>();
        values.add(new AttributeValue(type, new ASN1OctetString(message)));
        attrList.add(new Attribute(type, ATTR_TASK_LOG_MESSAGES, values));
        taskEntry.putAttribute(type, attrList);
      }
      else if (attrList.isEmpty())
      {
        LinkedHashSet<AttributeValue> values =
             new LinkedHashSet<AttributeValue>();
        values.add(new AttributeValue(type, new ASN1OctetString(message)));
        attrList.add(new Attribute(type, ATTR_TASK_LOG_MESSAGES, values));
      }
      else
      {
        Attribute attr = attrList.get(0);
        LinkedHashSet<AttributeValue> values = attr.getValues();
        values.add(new AttributeValue(type, new ASN1OctetString(message)));
        attrList.add(new Attribute(type, ATTR_TASK_LOG_MESSAGES, values));
      }
    }
    finally
    {
      taskScheduler.unlockEntry(taskEntryDN, lock);
    }
  }



  /**
   * Compares this task with the provided task for the purposes of ordering in a
   * sorted list.  Any completed task will always be ordered before an
   * uncompleted task.  If both tasks are completed, then they will be ordered
   * by completion time.  If both tasks are uncompleted, then a running task
   * will always be ordered before one that has not started.  If both are
   * running, then they will be ordered by actual start time.  If neither have
   * started, then they will be ordered by scheduled start time.  If all else
   * fails, they will be ordered lexicographically by task ID.
   *
   * @param  task  The task to compare with this task.
   *
   * @return  A negative value if the provided task should come before this
   *          task, a positive value if the provided task should come after this
   *          task, or zero if there is no difference with regard to their
   *          order.
   */
  public final int compareTo(Task task)
  {
    assert debugEnter(CLASS_NAME, "compareTo", String.valueOf(task));

    if (completionTime > 0)
    {
      if (task.completionTime > 0)
      {
        // They have both completed, so order by completion time.
        if (completionTime < task.completionTime)
        {
          return -1;
        }
        else if (completionTime > task.completionTime)
        {
          return 1;
        }
        else
        {
          // They have the same completion time, so order by task ID.
          return taskID.compareTo(task.taskID);
        }
      }
      else
      {
        // Completed tasks are always ordered before those that haven't
        // completed.
        return -1;
      }
    }
    else if (task.completionTime > 0)
    {
      // Completed tasks are always ordered before those that haven't completed.
      return 1;
    }

    if (actualStartTime > 0)
    {
      if (task.actualStartTime > 0)
      {
        // They are both running, so order by actual start time.
        if (actualStartTime < task.actualStartTime)
        {
          return -1;
        }
        else if (actualStartTime > task.actualStartTime)
        {
          return 1;
        }
        else
        {
          // They have the same actual start time, so order by task ID.
          return taskID.compareTo(task.taskID);
        }
      }
      else
      {
        // Running tasks are always ordered before those that haven't started.
        return -1;
      }
    }
    else if (task.actualStartTime > 0)
    {
      // Running tasks are always ordered before those that haven't started.
      return 1;
    }


    // Neither task has started, so order by scheduled start time, or if nothing
    // else by task ID.
    if (scheduledStartTime < task.scheduledStartTime)
    {
      return -1;
    }
    else if (scheduledStartTime > task.scheduledStartTime)
    {
      return 1;
    }
    else
    {
      return taskID.compareTo(task.taskID);
    }
  }



  /**
   * Begins execution for this task.  This is a wrapper around the
   * <CODE>runTask</CODE> method that performs the appropriate set-up and
   * tear-down.   It should only be invoked by a task thread.
   *
   * @return  The final state to use for the task.
   */
  public final TaskState execute()
  {
    assert debugEnter(CLASS_NAME, "execute");

    setActualStartTime(TimeThread.getTime());
    setTaskState(TaskState.RUNNING);
    taskScheduler.writeState();

    try
    {
      TaskState taskState = runTask();
      setTaskState(taskState);
    }
    catch (Exception e)
    {
      assert debugException(CLASS_NAME, "execute", e);

      setTaskState(TaskState.STOPPED_BY_ERROR);

      int    msgID   = MSGID_TASK_EXECUTE_FAILED;
      String message = getMessage(msgID, String.valueOf(taskEntry.getDN()),
                                  stackTraceToSingleLineString(e));
      logError(ErrorLogCategory.TASK, ErrorLogSeverity.SEVERE_ERROR, message,
               msgID);
    }
    finally
    {
      setCompletionTime(TimeThread.getTime());
      taskScheduler.writeState();
    }

    // FIXME -- Send an e-mail message if appropriate.
    return taskState;
  }



  /**
   * Performs any task-specific initialization that may be required before
   * processing can start.  This default implementation does not do anything,
   * but subclasses may override it as necessary.  This method will be called at
   * the time the task is scheduled, and therefore any failure in this method
   * will be returned to the client.
   *
   * @throws  DirectoryException  If a problem occurs during initialization that
   *                              should be returned to the client.
   */
  public void initializeTask()
         throws DirectoryException
  {
    assert debugEnter(CLASS_NAME, "initializeTask");

    // No action is performed by default.
  }



  /**
   * Performs the actual core processing for this task.  This method should not
   * return until all processing associated with this task has completed.
   *
   * @return  The final state to use for the task.
   */
  protected abstract TaskState runTask();



  /**
   * Performs any necessary processing to prematurely interrupt the execution of
   * this task.  By default no action is performed, but if it is feasible to
   * gracefully interrupt a task, then subclasses should override this method to
   * do so.
   *
   * @param  interruptState   The state to use for the task if it is
   *                          successfully interrupted.
   * @param  interruptReason  A human-readable explanation for the cancellation.
   */
  public void interruptTask(TaskState interruptState, String interruptReason)
  {
    assert debugEnter(CLASS_NAME, "interruptTask");

    // No action is performed by default.
  }
}

