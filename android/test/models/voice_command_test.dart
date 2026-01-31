import 'package:flutter_test/flutter_test.dart';
import 'package:speech2prompt/models/voice_command.dart';

void main() {
  group('CommandCode', () {
    test('parses enter commands', () {
      expect(CommandCode.parse('new line'), CommandCode.enter);
      expect(CommandCode.parse('newline'), CommandCode.enter);
      expect(CommandCode.parse('enter'), CommandCode.enter);
    });

    test('parses select all command', () {
      expect(CommandCode.parse('select all'), CommandCode.selectAll);
    });

    test('parses copy commands', () {
      expect(CommandCode.parse('copy that'), CommandCode.copy);
      expect(CommandCode.parse('copy this'), CommandCode.copy);
    });

    test('parses paste command', () {
      expect(CommandCode.parse('paste'), CommandCode.paste);
    });

    test('parses cut commands', () {
      expect(CommandCode.parse('cut that'), CommandCode.cut);
    });

    test('parses cancel commands', () {
      expect(CommandCode.parse('cancel'), CommandCode.cancel);
      expect(CommandCode.parse('never mind'), CommandCode.cancel);
    });

    test('returns null for unrecognized text', () {
      expect(CommandCode.parse('hello world'), isNull);
    });
  });

  group('ProcessedSpeech', () {
    test('processes text without commands', () {
      final result = ProcessedSpeech.process('hello world');

      expect(result.textBefore, 'hello world');
      expect(result.command, isNull);
      expect(result.textAfter, isNull);
    });

    test('processes text with command at end', () {
      final result = ProcessedSpeech.process('hello world new line');

      expect(result.textBefore, 'hello world');
      expect(result.command, CommandCode.enter);
      expect(result.textAfter, isNull);
    });

    test('processes text with command in middle', () {
      final result = ProcessedSpeech.process('hello new line world');

      expect(result.textBefore, 'hello');
      expect(result.command, CommandCode.enter);
      expect(result.textAfter, 'world');
    });

    test('processes command at start', () {
      final result = ProcessedSpeech.process('select all now');

      expect(result.textBefore, isNull);
      expect(result.command, CommandCode.selectAll);
      expect(result.textAfter, 'now');
    });
  });
}
