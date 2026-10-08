import tv.aurora.player.GuideName;
public class GuideNameTest {
 static int passed;
 static void expect(String title,String expected) {
  String got=GuideName.normalized(title);
  if(!expected.equals(got))
   throw new AssertionError("For '"+title+"' wanted '"+expected+"' but got '"+got+"'");
  passed++;
 }
 public static void main(String[] args) {
  expect("|4K| TF1 HDR/UHD/4K","TF1");
  expect("|4K| NESN UHD/4K+","NESN");
  expect("UK: BBC One HD","BBCONE");
  expect("[US] NBC Sports 1080p","NBCSPORTS");
  expect("ESPN HD","ESPN");
  expect("FR | TF1 FHD","TF1");
  expect("BBC One [EN]","BBCONE");
  expect("BBCOne.uk","BBCONEUK"); // cleanedId handles country suffix separately
  if(!"BBCONE".equals(GuideName.cleanedId("BBCOne.uk")))
   throw new AssertionError("XMLTV identifier cleanup");
  passed++;
  System.out.println(passed+" guide name tests passed");
 }
}