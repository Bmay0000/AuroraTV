import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aurora_tv/screen_awake.dart';

void main(){
 TestWidgetsFlutterBinding.ensureInitialized();
 const channel=MethodChannel('aurora.tv/screen_awake');

 test('Fire TV playback and preview independently request the wake flag',() async {
   final calls=<MethodCall>[];
   TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
     .setMockMethodCallHandler(channel,(call)async{
       calls.add(call);
       return true;
     });
   addTearDown((){
     TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
       .setMockMethodCallHandler(channel,null);
   });

   await ScreenAwake.set('player',true);
   await ScreenAwake.set('guide',true);
   await ScreenAwake.set('guide',false);
   await ScreenAwake.set('player',false);

   expect(calls.length,4);
   expect(calls.map((call)=>call.method),everyElement('set'));
   expect(calls[0].arguments,{'owner':'player','enabled':true});
   expect(calls[1].arguments,{'owner':'guide','enabled':true});
   expect(calls[2].arguments,{'owner':'guide','enabled':false});
   expect(calls[3].arguments,{'owner':'player','enabled':false});
 });
}
