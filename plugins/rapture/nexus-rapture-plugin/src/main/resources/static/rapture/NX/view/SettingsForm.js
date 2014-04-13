/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2014 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
/**
 * Abstract settings form.
 *
 * @since 3.0
 */
Ext.define('NX.view.SettingsForm', {
  extend: 'Ext.form.Panel',
  alias: 'widget.nx-settingsform',

  /**
   * @cfg {boolean} [settingsForm=true] Marker that we have a settings form
   * ({NX.controller.SettingsForm} controller kicks in)
   */
  settingsForm: true,

  /**
   * @cfg {boolean} [settingsFormSubmit=true] True if settings form should be submitted automatically when 'submit'
   * button is clicked. Set this to false if custom processing is needed.
   */
  settingsFormSubmit: true,

  /**
   * @cfg {boolean} [settingsFormSubmitOnEnter=false] True if form should be submitted on Enter.
   */
  settingsFormSubmitOnEnter: false,

  /**
   * @cfg {string/function} Text to be used when displaying submit/load messages. If is a function it will be called
   * with submit/load response data as parameter and it should return a String.
   * If text contains "${action}", it will be replaced with performed action.
   */
  settingsFormSuccessMessage: undefined,

  /**
   * @cfg {string/function} [settingsFormLoadMessage: 'Loading...'] Text to be used as mask while loading data.
   */
  settingsFormLoadMessage: 'Loading...',

  /**
   * @cfg {string/function} [settingsFormSubmitMessage: 'Saving...'] Text to be used as mask while submitting data.
   */
  settingsFormSubmitMessage: 'Saving...',

  /**
   * @cfg {NX.util.condition.Condition} The condition to be satisfied in order for this form to be editable.
   */
  editableCondition: undefined,

  /**
   * @cfg {string} Optional text to be shown in case that form is not editable (condition is not satisfied).
   */
  editableMarker: undefined,

  bodyPadding: 10,
  autoScroll: true,
  waitMsgTarget: true,

  defaults: {
    xtype: 'textfield',
    allowBlank: false
  },

  fieldDefaults: {
    labelAlign: 'top'
  },

  buttonAlign: 'left',

  buttons: [
    { text: 'Save', formBind: true, action: 'save', ui: 'primary', bindToEnter: false },
    { text: 'Discard',
      handler: function () {
        var form = this.up('form'),
            record = form.getRecord();

        if (record) {
          form.loadRecord(record);
        }
        else if (form.api && form.api.load) {
          form.load();
        }
        else {
          form.getForm().reset();
        }
      }
    }
  ],

  /**
   * @override
   */
  initComponent: function () {
    var me = this;

    if (me.buttons && Ext.isArray(me.buttons) && me.buttons[0] && Ext.isDefined(me.buttons[0].bindToEnter)) {
      me.buttons[0].bindToEnter = me.settingsFormSubmitOnEnter;
    }

    me.callParent(arguments);

    me.addEvents(
        /**
         * @event recordloaded
         * Fires when a record is loaded via {@link Ext.form.Panel#loadRecord}.
         * @param {Ext.form.Panel} this form
         * @param {Ext.data.Model} loaded record
         */
        'recordloaded',
        /**
         * @event loaded
         * Fires after form was loaded via configured api.
         * @param {Ext.form.Panel} this form
         * @param {Ext.form.action.Action} load action
         */
        'loaded',
        /**
         * @event submitted
         * Fires after form was submitted via configured api.
         * @param {Ext.form.Panel} this form
         * @param {Ext.form.action.Action} submit action
         */
        'submitted'
    );
  },

  /**
   * @override
   * Fires 'recordloaded' after record was loaded.
   */
  loadRecord: function (record) {
    var me = this;

    me.callParent(arguments);
    me.fireEvent('recordloaded', me, record);
  },

  /**
   * @public
   * Sets the read only state for all fields of this form.
   * @param {boolean} editable
   */
  setEditable: function (editable) {
    var me = this,
        itemsToDisable = me.getChildItemsToDisable(),
        bottomBar;

    if (editable) {
      Ext.Array.each(itemsToDisable, function (item) {
        var enable = true,
            form;

        if (item.resetEditable) {
          if (Ext.isFunction(item.setReadOnly)) {
            item.setReadOnly(false);
          }
          else {
            if (Ext.isDefined(item.resetFormBind)) {
              item.formBind = item.resetFormBind;
            }
            if (item.formBind) {
              form = item.up('form');
              if (form && !form.isValid()) {
                enable = false;
              }
            }
            if (enable) {
              item.enable();
            }
          }
        }
        if (Ext.isDefined(item.resetEditable)) {
          delete item.resetEditable;
          delete item.resetFormBind;
        }
      });
    }
    else {
      Ext.Array.each(itemsToDisable, function (item) {
        if (Ext.isFunction(item.setReadOnly)) {
          if (item.resetEditable !== false && !item.readOnly) {
            item.setReadOnly(true);
            item.resetEditable = true;
          }
        }
        else {
          if (item.resetEditable !== false && !item.disabled) {
            item.disable();
            item.resetFormBind = item.formBind;
            delete item.formBind;
            item.resetEditable = true;
          }
        }
      });
    }

    bottomBar = me.getDockedItems('toolbar[dock="bottom"]')[0];
    if (bottomBar) {
      if (editable) {
        if (bottomBar.editableMarker) {
          bottomBar.remove(bottomBar.editableMarker);
        }
      }
      else {
        if (me.editableMarker) {
          bottomBar.editableMarker = Ext.widget({
            xtype: 'label',
            text: me.editableMarker,
            // TODO replace style with css class?
            style: {
              fontSize: '10px',
              fontWeight: 'bold'
            }
          });
          bottomBar.add(bottomBar.editableMarker);
        }
      }
    }
  }

});
