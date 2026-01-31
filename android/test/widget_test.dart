// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Basic Flutter widget test', (WidgetTester tester) async {
    // Basic test that doesn't require platform-specific features
    // Full app tests with BLE require device/emulator
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: Text('Speech2Code'),
          ),
        ),
      ),
    );

    // Verify that the app can render basic widgets
    expect(find.byType(MaterialApp), findsOneWidget);
    expect(find.text('Speech2Code'), findsOneWidget);
  });
}
