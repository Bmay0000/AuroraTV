import 'package:flutter_test/flutter_test.dart';
import 'package:aurora_tv/catalog.dart';

void main(){
  test('Provider prefixes are removed from movie labels',(){
    const item=MediaEntry(id:'1',title:'EN - The Lighthouse (2024)',kind:MediaKind.movie);
    expect(item.cleanTitle,'The Lighthouse (2024)');
    expect(MediaEntry.normalize(item.cleanTitle),'thelighthouse');
  });
  test('English region tags are recognized without guessing language from title',(){
    const english=MediaEntry(id:'2',title:'Example',kind:MediaKind.live,category:'US | News');
    const foreign=MediaEntry(id:'3',title:'Example',kind:MediaKind.live,category:'FR | News');
    expect(english.likelyEnglish,isTrue);
    expect(foreign.likelyEnglish,isFalse);
  });
}
