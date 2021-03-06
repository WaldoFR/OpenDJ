/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2008-2010 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.guitools.controlpanel.ui;

import static org.opends.messages.AdminToolMessages.*;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.TreePath;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.Entry;
import org.opends.guitools.controlpanel.browser.BasicNodeError;
import org.opends.guitools.controlpanel.browser.BrowserController;
import org.opends.guitools.controlpanel.datamodel.ControlPanelInfo;
import org.opends.guitools.controlpanel.datamodel.ServerDescriptor;
import org.opends.guitools.controlpanel.event.ConfigurationChangeEvent;
import org.opends.guitools.controlpanel.event.EntryReadErrorEvent;
import org.opends.guitools.controlpanel.event.EntryReadEvent;
import org.opends.guitools.controlpanel.event.EntryReadListener;
import org.opends.guitools.controlpanel.event.LDAPEntryChangedEvent;
import org.opends.guitools.controlpanel.event.LDAPEntryChangedListener;
import org.opends.guitools.controlpanel.task.DeleteEntryTask;
import org.opends.guitools.controlpanel.task.ModifyEntryTask;
import org.opends.guitools.controlpanel.task.Task;
import org.opends.guitools.controlpanel.util.Utilities;
import org.opends.server.config.ConfigConstants;
import org.opends.server.types.OpenDsException;
import org.opends.server.util.ServerConstants;

/** This is the panel that contains all the different views to display an entry. */
public class LDAPEntryPanel extends StatusGenericPanel
implements EntryReadListener
{
  private static final long serialVersionUID = -6608246173472437830L;
  private JButton saveChanges;
  private JButton delete;
  private JPanel mainPanel;
  private CardLayout cardLayout;

  private ErrorSearchingEntryPanel errorSearchingPanel;
  private LDIFViewEntryPanel ldifEntryPanel;
  private TableViewEntryPanel tableEntryPanel;
  private SimplifiedViewEntryPanel simplifiedEntryPanel;

  private ViewEntryPanel displayedEntryPanel;

  private Entry searchResult;
  private BrowserController controller;
  private TreePath treePath;

  private ModifyEntryTask newTask;

  private final String NOTHING_SELECTED = "Nothing Selected";
  private final String MULTIPLE_SELECTED = "Multiple Selected";
  private final String LDIF_VIEW = "LDIF View";
  private final String ATTRIBUTE_VIEW = "Attribute View";
  private final String SIMPLIFIED_VIEW = "Simplified View";
  private final String ERROR_SEARCHING = "Error Searching";

  private View view = View.SIMPLIFIED_VIEW;

  /** The different views that we have to display an LDAP entry. */
  public enum View
  {
    /** Simplified view. */
    SIMPLIFIED_VIEW,
    /** Attribute view (contained in a table). */
    ATTRIBUTE_VIEW,
    /** LDIF view (text based). */
    LDIF_VIEW
  }

  /** Default constructor. */
  public LDAPEntryPanel()
  {
    super();
    createLayout();
  }

  /** Creates the layout of the panel (but the contents are not populated here). */
  private void createLayout()
  {
    GridBagConstraints gbc = new GridBagConstraints();
    cardLayout = new CardLayout();
    mainPanel = new JPanel(cardLayout);
    mainPanel.setOpaque(false);
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.gridwidth = 2;
    gbc.weightx = 1.0;
    gbc.weighty = 1.0;
    gbc.fill = GridBagConstraints.BOTH;
    add(mainPanel, gbc);
    gbc.gridwidth = 1;
    gbc.anchor = GridBagConstraints.WEST;
    gbc.insets = new Insets(5, 5, 5, 5);
    gbc.weighty = 0.0;
    gbc.gridy ++;
    gbc.fill = GridBagConstraints.NONE;
    delete = Utilities.createButton(INFO_CTRL_PANEL_DELETE_ENTRY_BUTTON.get());
    delete.setOpaque(false);
    add(delete, gbc);
    delete.addActionListener(new ActionListener()
    {
      @Override
      public void actionPerformed(ActionEvent ev)
      {
        deleteEntry();
      }
    });

    gbc.anchor = GridBagConstraints.EAST;
    gbc.gridx ++;
    saveChanges =
      Utilities.createButton(INFO_CTRL_PANEL_SAVE_CHANGES_LABEL.get());
    saveChanges.setOpaque(false);
    add(saveChanges, gbc);
    saveChanges.addActionListener(new ActionListener()
    {
      @Override
      public void actionPerformed(ActionEvent ev)
      {
        saveChanges(true);
      }
    });

    Border border = new EmptyBorder(10, 10, 10, 10);

    NoItemSelectedPanel noEntryPanel = new NoItemSelectedPanel();
    noEntryPanel.setMessage(INFO_CTRL_PANEL_NO_ENTRY_SELECTED_LABEL.get());
    Utilities.setBorder(noEntryPanel, border);
    mainPanel.add(noEntryPanel, NOTHING_SELECTED);

    NoItemSelectedPanel multipleEntryPanel = new NoItemSelectedPanel();
    multipleEntryPanel.setMessage(
        INFO_CTRL_PANEL_MULTIPLE_ENTRIES_SELECTED_LABEL.get());
    Utilities.setBorder(multipleEntryPanel, border);
    mainPanel.add(multipleEntryPanel, MULTIPLE_SELECTED);

    errorSearchingPanel = new ErrorSearchingEntryPanel();
    if (errorSearchingPanel.requiresBorder())
    {
      Utilities.setBorder(multipleEntryPanel, border);
    }
    mainPanel.add(errorSearchingPanel, ERROR_SEARCHING);

    LDAPEntryChangedListener listener = new LDAPEntryChangedListener()
    {
      @Override
      public void entryChanged(LDAPEntryChangedEvent ev)
      {
        boolean enable = saveChanges.isVisible() &&
            !authenticationRequired(getInfo().getServerDescriptor());
        if (enable)
        {
          Entry entry = ev .getEntry();
          if (entry == null)
          {
            // Something changed that is wrong: assume the entry has been
            // modified, when the user tries to save we will inform of the
            // problem
            enable = true;
          }
          else
          {
            boolean modified = !entry.getName().equals(searchResult.getName())
                || !ModifyEntryTask.getModifications(entry, searchResult, getInfo()).isEmpty();
            enable = modified;
          }
        }
        saveChanges.setEnabled(enable);
      }
    };

    ldifEntryPanel = new LDIFViewEntryPanel();
    ldifEntryPanel.addLDAPEntryChangedListener(listener);
    if (ldifEntryPanel.requiresBorder())
    {
      Utilities.setBorder(ldifEntryPanel, border);
    }
    mainPanel.add(ldifEntryPanel, LDIF_VIEW);

    tableEntryPanel = new TableViewEntryPanel();
    tableEntryPanel.addLDAPEntryChangedListener(listener);
    if (tableEntryPanel.requiresBorder())
    {
      Utilities.setBorder(tableEntryPanel, border);
    }
    mainPanel.add(tableEntryPanel, ATTRIBUTE_VIEW);

    simplifiedEntryPanel = new SimplifiedViewEntryPanel();
    simplifiedEntryPanel.addLDAPEntryChangedListener(listener);
    if (simplifiedEntryPanel.requiresBorder())
    {
      Utilities.setBorder(simplifiedEntryPanel, border);
    }
    mainPanel.add(simplifiedEntryPanel, SIMPLIFIED_VIEW);

    cardLayout.show(mainPanel, NOTHING_SELECTED);
  }

  @Override
  public void okClicked()
  {
    // No ok button
  }

  @Override
  public void entryRead(EntryReadEvent ev)
  {
    searchResult = ev.getSearchResult();

    updateEntryView(searchResult, treePath);
  }

  /**
   * Updates the panel with the provided search result.
   * @param searchResult the search result corresponding to the selected node.
   * @param treePath the tree path of the selected node.
   */
  private void updateEntryView(Entry searchResult, TreePath treePath)
  {
    boolean isReadOnly = isReadOnly(searchResult.getName());
    boolean canDelete = canDelete(searchResult.getName());

    delete.setVisible(canDelete);
    saveChanges.setVisible(!isReadOnly);
    String cardKey;
    switch (view)
    {
    case LDIF_VIEW:
      displayedEntryPanel = ldifEntryPanel;
      cardKey = LDIF_VIEW;
      break;
    case ATTRIBUTE_VIEW:
      displayedEntryPanel = tableEntryPanel;
      cardKey = ATTRIBUTE_VIEW;
      break;
    default:
      displayedEntryPanel = simplifiedEntryPanel;
      cardKey = SIMPLIFIED_VIEW;
    }
    displayedEntryPanel.update(searchResult, isReadOnly, treePath);
    saveChanges.setEnabled(false);
    cardLayout.show(mainPanel, cardKey);
  }

  /**
   * Sets the view to be displayed by this panel.
   * @param view the view.
   */
  public void setView(View view)
  {
    if (view != this.view)
    {
      this.view = view;
      if (searchResult != null)
      {
        updateEntryView(searchResult, treePath);
      }
    }
  }

  /**
   * Displays a message informing that an error occurred reading the entry.
   * @param ev the entry read error event.
   */
  @Override
  public void entryReadError(EntryReadErrorEvent ev)
  {
    searchResult = null;

    errorSearchingPanel.setError(ev.getDN(), ev.getError());

    delete.setVisible(false);
    saveChanges.setVisible(false);

    cardLayout.show(mainPanel, ERROR_SEARCHING);

    displayedEntryPanel = null;
  }

  /**
   * Displays a message informing that an error occurred resolving a referral.
   * @param dn the DN of the local entry.
   * @param referrals the list of referrals defined in the entry.
   * @param error the error that occurred resolving the referral.
   */
  public void referralSolveError(DN dn, String[] referrals, BasicNodeError error)
  {
    searchResult = null;

    errorSearchingPanel.setReferralError(dn, referrals, error);

    delete.setVisible(false);
    saveChanges.setVisible(false);

    cardLayout.show(mainPanel, ERROR_SEARCHING);

    displayedEntryPanel = null;
  }

  /** Displays a panel informing that nothing is selected. */
  public void noEntrySelected()
  {
    searchResult = null;

    delete.setVisible(false);
    saveChanges.setVisible(false);

    cardLayout.show(mainPanel, NOTHING_SELECTED);

    displayedEntryPanel = null;
  }

  /** Displays a panel informing that multiple entries are selected. */
  public void multipleEntriesSelected()
  {
    searchResult = null;

    delete.setVisible(false);
    saveChanges.setVisible(false);

    cardLayout.show(mainPanel, MULTIPLE_SELECTED);

    displayedEntryPanel = null;
  }

  @Override
  public GenericDialog.ButtonType getButtonType()
  {
    return GenericDialog.ButtonType.NO_BUTTON;
  }

  @Override
  public LocalizableMessage getTitle()
  {
    return INFO_CTRL_PANEL_EDIT_LDAP_ENTRY_TITLE.get();
  }

  @Override
  public Component getPreferredFocusComponent()
  {
    return saveChanges;
  }

  @Override
  public void configurationChanged(ConfigurationChangeEvent ev)
  {
    final ServerDescriptor desc = ev.getNewDescriptor();
    SwingUtilities.invokeLater(new Runnable()
    {
      @Override
      public void run()
      {
        boolean isReadOnly = true;
        boolean canDelete = false;
        if (searchResult != null && desc.isAuthenticated())
        {
          isReadOnly = isReadOnly(searchResult.getName());
          canDelete = canDelete(searchResult.getName());
        }

        delete.setVisible(canDelete);
        saveChanges.setVisible(!isReadOnly);
      }
    });
  }

  @Override
  public void setInfo(ControlPanelInfo info)
  {
    super.setInfo(info);
    simplifiedEntryPanel.setInfo(info);
    ldifEntryPanel.setInfo(info);
    tableEntryPanel.setInfo(info);
    errorSearchingPanel.setInfo(info);
  }

  private List<DN> parentReadOnly;
  private List<DN> nonDeletable;
  {
    try
    {
      parentReadOnly = Arrays.asList(
        DN.valueOf(ConfigConstants.DN_TASK_ROOT),
        DN.valueOf(ConfigConstants.DN_MONITOR_ROOT),
        DN.valueOf(ConfigConstants.DN_BACKUP_ROOT),
        DN.valueOf(ServerConstants.DN_EXTERNAL_CHANGELOG_ROOT)
      );
      nonDeletable = Arrays.asList(
          DN.valueOf(ConfigConstants.DN_CONFIG_ROOT),
          DN.valueOf(ConfigConstants.DN_DEFAULT_SCHEMA_ROOT),
          DN.valueOf(ConfigConstants.DN_TRUST_STORE_ROOT)
      );
    }
    catch (Throwable t)
    {
      throw new RuntimeException("Error decoding DNs: "+t, t);
    }
  }

  /**
   * Returns <CODE>true</CODE> if the provided DN corresponds to a read-only
   * entry and <CODE>false</CODE> otherwise.
   * @param sDn the DN of the entry.
   * @return <CODE>true</CODE> if the provided DN corresponds to a read-only
   * entry and <CODE>false</CODE> otherwise.
   */
  private boolean isReadOnly(DN dn)
  {
    for (DN parentDN : parentReadOnly)
    {
      if (dn.isSubordinateOrEqualTo(parentDN))
      {
        return true;
      }
    }
    return dn.equals(DN.rootDN());
  }

  /**
   * Returns <CODE>true</CODE> if the provided DN corresponds to an entry that
   * can be deleted and <CODE>false</CODE> otherwise.
   * @param dn the DN of the entry.
   * @return <CODE>true</CODE> if the provided DN corresponds to an entry that
   * can be deleted and <CODE>false</CODE> otherwise.
   */
  public boolean canDelete(DN dn)
  {
    try
    {
      return !dn.equals(DN.rootDN())
          && !nonDeletable.contains(dn)
          && isDescendantOfAny(dn, parentReadOnly);
    }
    catch (Throwable t)
    {
      throw new RuntimeException("Error decoding DNs: "+t, t);
    }
  }

  private boolean isDescendantOfAny(DN dn, List<DN> parentDNs)
  {
    for (DN parentDN : parentDNs)
    {
      if (dn.isSubordinateOrEqualTo(parentDN))
      {
        return false;
      }
    }
    return true;
  }

  /**
   * Saves the changes done to the entry.
   * @param modal whether the progress dialog for the task must be modal or
   * not.
   */
  private void saveChanges(boolean modal)
  {
    newTask = null;
    final ArrayList<LocalizableMessage> errors = new ArrayList<>();
    // Check that the entry is correct.
    try
    {
      ProgressDialog dlg = new ProgressDialog(
          Utilities.getFrame(this),
          Utilities.getFrame(this),
          INFO_CTRL_PANEL_MODIFYING_ENTRY_CHANGES_TITLE.get(), getInfo());
      dlg.setModal(modal);
      Entry entry = displayedEntryPanel.getEntry();
      newTask = new ModifyEntryTask(getInfo(), dlg, entry, searchResult, controller, treePath);
      for (Task task : getInfo().getTasks())
      {
        task.canLaunch(newTask, errors);
      }

      if (errors.isEmpty())
      {
        if (newTask.hasModifications()) {
          String dn = entry.getName().toString();
          launchOperation(newTask,
              INFO_CTRL_PANEL_MODIFYING_ENTRY_SUMMARY.get(dn),
              INFO_CTRL_PANEL_MODIFYING_ENTRY_COMPLETE.get(),
              INFO_CTRL_PANEL_MODIFYING_ENTRY_SUCCESSFUL.get(dn),
              ERR_CTRL_PANEL_MODIFYING_ENTRY_ERROR_SUMMARY.get(),
              ERR_CTRL_PANEL_MODIFYING_ENTRY_ERROR_DETAILS.get(dn),
              null,
              dlg);
          saveChanges.setEnabled(false);
          dlg.setVisible(true);
        }
        else
        {
          // Mark the panel as it has no changes.  This can happen because every
          // time the user types something the saveChanges button is enabled
          // (for performance reasons with huge entries).
          saveChanges.setEnabled(false);
        }
      }
    }
    catch (OpenDsException ode)
    {
      errors.add(ERR_CTRL_PANEL_INVALID_ENTRY.get(ode.getMessageObject()));
    }
    if (!errors.isEmpty())
    {
      displayErrorDialog(errors);
    }
  }

  private void deleteEntry()
  {
    final ArrayList<LocalizableMessage> errors = new ArrayList<>();
    // Check that the entry is correct.
    // Rely in numsubordinates and hassubordinates
    boolean isLeaf = !BrowserController.getHasSubOrdinates(searchResult);

    if (treePath != null)
    {
      LocalizableMessage title = isLeaf ? INFO_CTRL_PANEL_DELETING_ENTRY_TITLE.get() :
        INFO_CTRL_PANEL_DELETING_SUBTREE_TITLE.get();
      ProgressDialog dlg = new ProgressDialog(
          Utilities.createFrame(),
          Utilities.getParentDialog(this), title, getInfo());
      DeleteEntryTask newTask = new DeleteEntryTask(getInfo(), dlg,
          new TreePath[]{treePath}, controller);
      for (Task task : getInfo().getTasks())
      {
        task.canLaunch(newTask, errors);
      }
      if (errors.isEmpty())
      {
        LocalizableMessage confirmationMessage =
          isLeaf ? INFO_CTRL_PANEL_DELETE_ENTRY_CONFIRMATION_DETAILS.get(
              searchResult.getName()) :
                INFO_CTRL_PANEL_DELETE_SUBTREE_CONFIRMATION_DETAILS.get(
                    searchResult.getName());
          if (displayConfirmationDialog(
              INFO_CTRL_PANEL_CONFIRMATION_REQUIRED_SUMMARY.get(),
              confirmationMessage))
          {
            DN dn = searchResult.getName();
            if (isLeaf)
            {
              launchOperation(newTask,
                  INFO_CTRL_PANEL_DELETING_ENTRY_SUMMARY.get(dn),
                  INFO_CTRL_PANEL_DELETING_ENTRY_COMPLETE.get(),
                  INFO_CTRL_PANEL_DELETING_ENTRY_SUCCESSFUL.get(dn),
                  ERR_CTRL_PANEL_DELETING_ENTRY_ERROR_SUMMARY.get(),
                  ERR_CTRL_PANEL_DELETING_ENTRY_ERROR_DETAILS.get(dn),
                  null,
                  dlg);
            }
            else
            {
              launchOperation(newTask,
                  INFO_CTRL_PANEL_DELETING_SUBTREE_SUMMARY.get(dn),
                  INFO_CTRL_PANEL_DELETING_SUBTREE_COMPLETE.get(),
                  INFO_CTRL_PANEL_DELETING_SUBTREE_SUCCESSFUL.get(dn),
                  ERR_CTRL_PANEL_DELETING_SUBTREE_ERROR_SUMMARY.get(),
                  ERR_CTRL_PANEL_DELETING_SUBTREE_ERROR_DETAILS.get(dn),
                  null,
                  dlg);
            }
            dlg.setVisible(true);
          }
      }
    }
    if (!errors.isEmpty())
    {
      displayErrorDialog(errors);
    }
  }

  /**
   * Returns the browser controller in charge of the tree.
   * @return the browser controller in charge of the tree.
   */
  public BrowserController getController()
  {
    return controller;
  }

  /**
   * Sets the browser controller in charge of the tree.
   * @param controller the browser controller in charge of the tree.
   */
  public void setController(BrowserController controller)
  {
    this.controller = controller;
  }

  /**
   * Returns the tree path associated with the node that is being displayed.
   * @return the tree path associated with the node that is being displayed.
   */
  public TreePath getTreePath()
  {
    return treePath;
  }

  /**
   * Sets the tree path associated with the node that is being displayed.
   * @param treePath the tree path associated with the node that is being
   * displayed.
   */
  public void setTreePath(TreePath treePath)
  {
    this.treePath = treePath;
  }

  /**
   * Method used to know if there are unsaved changes or not.  It is used by
   * the entry selection listener when the user changes the selection.
   * @return <CODE>true</CODE> if there are unsaved changes (and so the
   * selection of the entry should be cancelled) and <CODE>false</CODE>
   * otherwise.
   */
  public boolean mustCheckUnsavedChanges()
  {
    return displayedEntryPanel != null &&
        saveChanges.isVisible() && saveChanges.isEnabled();
  }

  /**
   * Tells whether the user chose to save the changes in the panel, to not save
   * them or simply canceled the selection change in the tree.
   * @return the value telling whether the user chose to save the changes in the
   * panel, to not save them or simply canceled the selection in the tree.
   */
  public UnsavedChangesDialog.Result checkUnsavedChanges()
  {
    UnsavedChangesDialog.Result result;
    UnsavedChangesDialog unsavedChangesDlg = new UnsavedChangesDialog(
          Utilities.getParentDialog(this), getInfo());
    unsavedChangesDlg.setMessage(INFO_CTRL_PANEL_UNSAVED_CHANGES_SUMMARY.get(),
       INFO_CTRL_PANEL_UNSAVED_ENTRY_CHANGES_DETAILS.get(searchResult.getName()));
    Utilities.centerGoldenMean(unsavedChangesDlg,
          Utilities.getParentDialog(this));
    unsavedChangesDlg.setVisible(true);
    result = unsavedChangesDlg.getResult();
    if (result == UnsavedChangesDialog.Result.SAVE)
    {
      saveChanges(false);
      if (newTask == null || // The user data is not valid
          newTask.getState() != Task.State.FINISHED_SUCCESSFULLY)
      {
        result = UnsavedChangesDialog.Result.CANCEL;
      }
    }

    return result;
  }
}
