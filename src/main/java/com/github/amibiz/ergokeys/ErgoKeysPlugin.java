/*
 * Copyright 2018 Ami E. Bizamcher. All rights reserved.
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

package com.github.amibiz.ergokeys;

import com.intellij.execution.configurations.RefactoringListenerProvider;
import com.intellij.execution.junit.RefactoringListeners;
import com.intellij.find.SearchReplaceComponent;
import com.intellij.find.actions.FindInPathAction;
import com.intellij.ide.actions.HideAllToolWindowsAction;
import com.intellij.ide.actions.SearchEverywhereAction;

import com.intellij.ide.actions.ShowSettingsAction;
import com.intellij.ide.actions.ViewStructureAction;
import com.intellij.ide.actions.runAnything.RunAnythingAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.actions.IncrementalFindAction;
import com.intellij.openapi.editor.actions.ReplaceAction;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManagerListener;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.actions.BaseRefactoringAction;
import com.intellij.refactoring.actions.RenameElementAction;
import com.intellij.refactoring.actions.RenameFileAction;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.ui.ComboBoxCompositeEditor;
import com.jediterm.terminal.ui.TerminalPanelListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.ArrayList;
import java.util.List;

public class ErgoKeysPlugin implements ApplicationComponent {

    private static final Logger LOG = Logger.getInstance(ErgoKeysPlugin.class);

    private static final String PLUGIN_ID = "com.github.amibiz.ergokeys";
    private static final String ROOT_ERGOKEYS_KEYMAP = "$ergokeys";
    private static final String DEFAULT_ERGOKEYS_KEYMAP = "ErgoKeys (QWERTY)";

    private final ErgoKeysSettings settings;
    private final Application application;
    private final KeymapManagerEx keymapManagerEx;
    private final PropertiesComponent propertiesComponent;

    private Keymap insertModeKeymap;
    private Keymap commandModeKeymap;

    private Editor lastEditorUsed;

    public ErgoKeysPlugin() {
        settings = ErgoKeysSettings.getInstance();
        application = ApplicationManager.getApplication();
        keymapManagerEx = KeymapManagerEx.getInstanceEx();
        propertiesComponent = PropertiesComponent.getInstance();
    }

//    @Override
//    public void disposeComponent() {
//    }

    @NotNull
    @Override
    public String getComponentName() {
        return "ErgoKeysPlugin";
    }

    @Override
    public void initComponent() {
        LOG.debug("initComponent");

        // NOTE: we use the keymap parent relationship to extend derived
        // shortcuts and to identify command mode keymaps. at this stage,
        // the keymaps parent reference is null (lazy).
        // to overcome this, we force all keymaps to load by calling the
        // getActionIdList() method.
        for (Keymap keymap : this.keymapManagerEx.getAllKeymaps()) {
            keymap.getActionIdList();
        }

        String insertModeKeymapName = this.loadPersistentProperty("insertModeKeymapName");
        if (insertModeKeymapName == null) {
            insertModeKeymap = keymapManagerEx.getActiveKeymap();
        } else {
            insertModeKeymap = keymapManagerEx.getKeymap(insertModeKeymapName);
            if (insertModeKeymap == null) {
                insertModeKeymap = keymapManagerEx.getKeymap("$default");
                assert insertModeKeymap != null;
            }
        }
        this.storePersistentProperty("insertModeKeymapName", insertModeKeymap.getName());

        String commandModeKeymapName = this.loadPersistentProperty("commandModeKeymapName");
        if (commandModeKeymapName == null) {
            commandModeKeymap = keymapManagerEx.getKeymap(DEFAULT_ERGOKEYS_KEYMAP);
            assert commandModeKeymap != null;
        } else {
            commandModeKeymap = keymapManagerEx.getKeymap(commandModeKeymapName);
            if (commandModeKeymap == null) {
                commandModeKeymap = keymapManagerEx.getKeymap(DEFAULT_ERGOKEYS_KEYMAP);
                assert commandModeKeymap != null;
            }
        }
        this.storePersistentProperty("commandModeKeymapName", commandModeKeymap.getName());

        extendCommandModeShortcuts(insertModeKeymap);

        ActionManager.getInstance().registerAction("ErgoKeysNoopAction", new AnAction() {
            @Override
            public void actionPerformed(AnActionEvent e) {
                // noop
            }
        });

        application.getMessageBus().connect().subscribe(KeymapManagerListener.TOPIC, new KeymapManagerListener() {

            @Override
            public void activeKeymapChanged(@Nullable Keymap keymap) {
                LOG.debug("activeKeymapChanged: keymap " + keymap.getName());

                if (keymap.equals(commandModeKeymap) || keymap.equals(insertModeKeymap)) {
                    return;
                }

                String key;
                if (isErgoKeysKeymap(keymap)) {
                    commandModeKeymap = keymap;
                    key = "commandModeKeymapName";
                } else {
                    purgeCommandModeShortcuts(insertModeKeymap);
                    insertModeKeymap = keymap;
                    key = "insertModeKeymapName";
                    extendCommandModeShortcuts(insertModeKeymap);
                    activateInsertMode(lastEditorUsed);
                }
                storePersistentProperty(key, keymap.getName());
            }
        });

                ApplicationManager.getApplication().getMessageBus().connect().subscribe(AnActionListener.TOPIC, new AnActionListener() {
                    @Override
                    public void beforeActionPerformed(@NotNull AnAction action, @NotNull DataContext dataContext, @NotNull AnActionEvent event) {
                        Class<? extends AnAction> thisAction = action.getClass();
                        LOG.debug("beforeActionPerformed: action.class=", thisAction);
                        ActionManager am = ActionManager.getInstance();

                        // Omar: I need checks here like for @FindInPathAction. If we try to rely solely on the focus
                        // lost event below, we end up in a weird state where the first 'i' works, but if any matches
                        // show up subsequent calls to 'i' or 'k' will scroll up and down options instead. Making it
                        // impossible to press these buttons. So before the action even launches we switch to activate
                        // mode. This seems to fix it; probably because we entirely skip command mode. Still not sure
                        // what causes the weird behavior without this. More may have to be added later.
                        if(thisAction.equals(FindInPathAction.class) ||
                           thisAction.equals(SearchEverywhereAction.class) ||
                           thisAction.equals(RenameFileAction.class) ||
                           action instanceof BaseRefactoringAction){
                            final Editor editor = dataContext.getData(CommonDataKeys.EDITOR);
                            activateInsertMode(editor);
                        }
                    }

                    @Override
                    public void afterActionPerformed(@NotNull AnAction action, @NotNull DataContext dataContext, @NotNull AnActionEvent event) {
                        LOG.debug("afterActionPerformed: action.class=", action.getClass());

//                        if(action instanceof HideAllToolWindowsAction){
//                            activateCommandMode(lastEditorUsed);
//                        }
                    }
                });

        EditorFactory.getInstance().addEditorFactoryListener(
                new EditorFactoryListener() {
                    @Override
                    public void editorCreated(@NotNull EditorFactoryEvent event) {
                        Editor editor = event.getEditor();

                        editor.getContentComponent().addFocusListener(new FocusListener() {
                            @Override
                            public void focusGained(FocusEvent focusEvent) {
                                LOG.debug("focusGained: focusEvent=", focusEvent.toString());

                                EditorComponentImpl e = (EditorComponentImpl)(focusEvent.getSource());
                                VirtualFile f = e.getEditor().getVirtualFile();
                                // No file name. Assume it is not the "main" editor windows where user modifies code.
                                if(f != null){
                                    activateCommandMode(editor);
                                }
                            }

                            @Override
                            public void focusLost(FocusEvent focusEvent) {
                                LOG.debug("focusLost: focusEvent=", focusEvent);
                                // Will we need this?
//                                lastEditorUsed = editor;
                                activateInsertMode(editor);
                            }
                        });
                    }

                    public void editorReleased(@NotNull EditorFactoryEvent event) {
                    }
                },
                new Disposable() {
                    @Override
                    public void dispose() {

                    }
                }
        );
    }

    public void applySettings() {
    }

    public void activateCommandMode(Editor editor) {
        LOG.debug("activateCommandMode");
        if (settings.isCommandModeToggle() && inCommandMode()) {
            activateInsertMode(editor);
            return;
        }
        editor.getSettings().setBlockCursor(true);
        this.keymapManagerEx.setActiveKeymap(commandModeKeymap);
    }

    public void activateInsertMode(Editor editor) {
        LOG.debug("activateInsertMode");
        editor.getSettings().setBlockCursor(false);
        this.keymapManagerEx.setActiveKeymap(insertModeKeymap);
    }

    private String persistentPropertyName(String key) {
        return PLUGIN_ID + "." + key;
    }

    private String loadPersistentProperty(String key) {
        return propertiesComponent.getValue(persistentPropertyName(key));
    }

    private void storePersistentProperty(String key, String value) {
        propertiesComponent.setValue(persistentPropertyName(key), value);
    }

    private boolean inCommandMode() {
        return isErgoKeysKeymap(keymapManagerEx.getActiveKeymap());
    }

    private void extendCommandModeShortcuts(@NotNull Keymap dst) {
        for (Keymap keymap : this.getAllErgoKeysKeymaps()) {
            this.extendShortcuts(keymap, dst);
        }
    }

    private void purgeCommandModeShortcuts(@NotNull Keymap dst) {
        for (Keymap keymap : this.getAllErgoKeysKeymaps()) {
            this.purgeShortcuts(keymap, dst);
        }
    }

    private void extendShortcuts(@NotNull Keymap dst, Keymap src) {
        for (String actionId : src.getActionIds()) {
            for (Shortcut shortcut : src.getShortcuts(actionId)) {
                dst.addShortcut(actionId, shortcut);
            }
        }
    }

    private void purgeShortcuts(@NotNull Keymap dst, Keymap src) {
        for (String actionId : src.getActionIds()) {
            for (Shortcut shortcut : src.getShortcuts(actionId)) {
                dst.removeShortcut(actionId, shortcut);
            }
        }
    }

    private Keymap[] getAllErgoKeysKeymaps() {
        List<Keymap> keymaps = new ArrayList<>();
        for (Keymap keymap : this.keymapManagerEx.getAllKeymaps()) {
            LOG.debug("getAllErgoKeysKeymaps: check keymap ", keymap);
            if (isErgoKeysKeymap(keymap)) {
                keymaps.add(keymap);
            }
        }
        return keymaps.toArray(new Keymap[0]);
    }

    private boolean isErgoKeysKeymap(@Nullable Keymap keymap) {
        for (; keymap != null; keymap = keymap.getParent()) {
            if (ROOT_ERGOKEYS_KEYMAP.equalsIgnoreCase(keymap.getName())) {
                return true;
            }
        }
        return false;
    }
}
