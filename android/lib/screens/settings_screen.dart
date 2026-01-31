// Copyright 2026 Daniel Pelikan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/speech_service.dart';
import '../services/bluetooth_service.dart';

/// Settings screen for app configuration.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  // Voice command toggles
  bool _enterEnabled = true;
  bool _selectAllEnabled = true;
  bool _copyEnabled = true;
  bool _pasteEnabled = true;
  bool _cutEnabled = true;
  bool _cancelEnabled = true;

  // Other settings
  bool _keepScreenOn = true;
  bool _showPartialResults = true;
  bool _autoReconnect = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _enterEnabled = prefs.getBool('cmd_enter') ?? true;
      _selectAllEnabled = prefs.getBool('cmd_select_all') ?? true;
      _copyEnabled = prefs.getBool('cmd_copy') ?? true;
      _pasteEnabled = prefs.getBool('cmd_paste') ?? true;
      _cutEnabled = prefs.getBool('cmd_cut') ?? true;
      _cancelEnabled = prefs.getBool('cmd_cancel') ?? true;
      _keepScreenOn = prefs.getBool('keep_screen_on') ?? true;
      _showPartialResults = prefs.getBool('show_partial') ?? true;
      _autoReconnect = prefs.getBool('auto_reconnect') ?? true;
    });
  }

  Future<void> _saveSetting(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(key, value);
  }

  @override
  Widget build(BuildContext context) {
    final speechService = context.read<SpeechService>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
      ),
      body: ListView(
        children: [
          // Speech Recognition Section
          _buildSectionHeader('Speech Recognition'),
          _buildLanguageTile(speechService),
          SwitchListTile(
            title: const Text('Show partial results'),
            subtitle: const Text('Display text as you speak'),
            value: _showPartialResults,
            onChanged: (value) {
              setState(() => _showPartialResults = value);
              _saveSetting('show_partial', value);
            },
          ),
          SwitchListTile(
            title: const Text('Keep screen on'),
            subtitle: const Text('Prevent screen from sleeping while listening'),
            value: _keepScreenOn,
            onChanged: (value) {
              setState(() => _keepScreenOn = value);
              _saveSetting('keep_screen_on', value);
            },
          ),

          const Divider(),

          // Voice Commands Section
          _buildSectionHeader('Voice Commands'),
          _buildCommandTile(
            'New line / Enter',
            'Say "new line" or "enter"',
            _enterEnabled,
            (value) {
              setState(() => _enterEnabled = value);
              _saveSetting('cmd_enter', value);
            },
          ),
          _buildCommandTile(
            'Select all',
            'Say "select all"',
            _selectAllEnabled,
            (value) {
              setState(() => _selectAllEnabled = value);
              _saveSetting('cmd_select_all', value);
            },
          ),
          _buildCommandTile(
            'Copy',
            'Say "copy that"',
            _copyEnabled,
            (value) {
              setState(() => _copyEnabled = value);
              _saveSetting('cmd_copy', value);
            },
          ),
          _buildCommandTile(
            'Paste',
            'Say "paste"',
            _pasteEnabled,
            (value) {
              setState(() => _pasteEnabled = value);
              _saveSetting('cmd_paste', value);
            },
          ),
          _buildCommandTile(
            'Cut',
            'Say "cut that"',
            _cutEnabled,
            (value) {
              setState(() => _cutEnabled = value);
              _saveSetting('cmd_cut', value);
            },
          ),
          _buildCommandTile(
            'Cancel',
            'Say "cancel" or "clear"',
            _cancelEnabled,
            (value) {
              setState(() => _cancelEnabled = value);
              _saveSetting('cmd_cancel', value);
            },
          ),

          const Divider(),

          // Connection Section
          _buildSectionHeader('Connection'),
          SwitchListTile(
            title: const Text('Auto-reconnect'),
            subtitle: const Text('Automatically reconnect if connection is lost'),
            value: _autoReconnect,
            onChanged: (value) {
              setState(() => _autoReconnect = value);
              _saveSetting('auto_reconnect', value);
            },
          ),
          ListTile(
            title: const Text('Paired device'),
            subtitle: Consumer<BluetoothService>(
              builder: (context, bt, _) {
                return Text(
                  bt.connectedDevice?.displayName ?? 'Not connected',
                );
              },
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.pushNamed(context, '/connection'),
          ),

          const Divider(),

          // About Section
          _buildSectionHeader('About'),
          ListTile(
            title: const Text('Version'),
            subtitle: const Text('1.0.0'),
          ),
          ListTile(
            title: const Text('Help & Feedback'),
            trailing: const Icon(Icons.open_in_new),
            onTap: () {
              // Open help URL
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Text(
        title,
        style: TextStyle(
          color: Theme.of(context).colorScheme.primary,
          fontWeight: FontWeight.w600,
          fontSize: 14,
        ),
      ),
    );
  }

  Widget _buildLanguageTile(SpeechService speechService) {
    return ListTile(
      title: const Text('Language'),
      subtitle: Text(speechService.selectedLocale),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => _showLanguagePicker(speechService),
    );
  }

  Future<void> _showLanguagePicker(SpeechService speechService) async {
    final locales = speechService.availableLocales;
    
    final selected = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Select Language'),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: locales.length,
            itemBuilder: (context, index) {
              final locale = locales[index];
              final isSelected = locale.localeId == speechService.selectedLocale;
              return ListTile(
                title: Text(locale.name),
                subtitle: Text(locale.localeId),
                trailing: isSelected ? const Icon(Icons.check) : null,
                onTap: () => Navigator.pop(context, locale.localeId),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );

    if (selected != null) {
      speechService.setLocale(selected);
    }
  }

  Widget _buildCommandTile(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> onChanged,
  ) {
    return SwitchListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      value: value,
      onChanged: onChanged,
    );
  }
}
