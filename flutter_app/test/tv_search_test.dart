import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aurora_tv/main.dart';

void main() {
  testWidgets('TV search opens keyboard only on activation and submits query',
      (tester) async {
    final controller=TextEditingController();
    String? submitted;
    addTearDown(controller.dispose);
    await tester.pumpWidget(MaterialApp(home:Scaffold(
      body:TvSearchField(controller:controller,hint:'Search titles',
        onSubmitted:(text) async {submitted=text;}))));
    // Merely drawing or focusing the selectable search surface never creates
    // an EditableText or Android keyboard connection.
    expect(find.byType(TextField),findsNothing);
    final button=find.byType(OutlinedButton);
    expect(button,findsOneWidget);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.byType(TextField),findsOneWidget);
    await tester.enterText(find.byType(TextField),'The Simpsons');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(submitted,'The Simpsons');
    expect(controller.text,'The Simpsons');
    expect(find.byType(TextField),findsNothing);
  });
}
