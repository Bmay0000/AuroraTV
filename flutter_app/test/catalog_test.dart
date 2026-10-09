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
  test('Standalone TV episode titles match only the intended show',(){
    final s1=EpisodeTitleMatcher.parse('The Simpsons (1989)','The Simpsons - S01E01 - Simpsons Roasting on an Open Fire');
    expect(s1?.season,1);
    expect(s1?.episode,1);
    final s2=EpisodeTitleMatcher.parse('The Simpsons','USA - The Simpsons 2x03');
    expect(s2?.season,2);
    expect(s2?.episode,3);
    final s3=EpisodeTitleMatcher.parse('The Simpsons','The Simpsons Season 12 Episode 5');
    expect(s3?.season,12);
    expect(s3?.episode,5);
    expect(EpisodeTitleMatcher.parse('The Simpsons','The Simpsons Movie (2007)'),isNull);
    expect(EpisodeTitleMatcher.parse('The Simpsons','The Simpsons Shorts S01E01'),isNull);
    expect(EpisodeTitleMatcher.parse('The Simpsons','Family Guy S01E01'),isNull);
  });
}
