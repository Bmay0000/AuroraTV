package tv.aurora.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Fire TV / Android TV D-pad-first, high-contrast option sheets.
 * An independent UI helper: does not access IPTV credentials or library state.
 */
public final class TvSheet {
    private static final int SURFACE=0xff101e2c, HIGHLIGHT=0xff24463f,
        BORDER=0xff34505a, ACCENT=0xff5debd0, MUTED=0xffaac0cd;
    private TvSheet(){}

    public static final class Entry {
        public final String section, icon, title, detail;
        public final Runnable click;
        public Entry(String section,String icon,String title,String detail,Runnable click){
            this.section=section;this.icon=icon;this.title=title;this.detail=detail;this.click=click;
        }
    }

    private static int dp(Activity a,int v){
        return Math.round(a.getResources().getDisplayMetrics().density*v);
    }
    private static GradientDrawable panel(Activity a,int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(a,radius));
        if(stroke!=0)d.setStroke(Math.max(1,dp(a,1)),stroke);
        return d;
    }
    private static TextView text(Activity a,String value,int size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);
        t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        return t;
    }
    private static final class Frame {
        Dialog dialog;
        LinearLayout content;
    }
    private static Frame open(Activity a,String title,String description){
        Dialog dialog=new Dialog(a);
        LinearLayout base=new LinearLayout(a);base.setOrientation(LinearLayout.VERTICAL);
        base.setPadding(dp(a,23),dp(a,20),dp(a,23),dp(a,17));
        base.setBackground(panel(a,0xff0b1624,0xff3a5360,20));
        TextView caption=text(a,"A U R O R A   /   SETTINGS",11,ACCENT,true);
        caption.setLetterSpacing(.13f);
        base.addView(caption,new LinearLayout.LayoutParams(-1,dp(a,21)));
        TextView name=text(a,title,26,Color.WHITE,true);
        name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLayout=new LinearLayout.LayoutParams(-1,dp(a,39));
        nameLayout.topMargin=dp(a,3);base.addView(name,nameLayout);
        if(description!=null&&!description.isEmpty()){
            TextView copy=text(a,description,13,MUTED,false);
            copy.setMaxLines(3);
            LinearLayout.LayoutParams copyLayout=new LinearLayout.LayoutParams(-1,-2);
            copyLayout.bottomMargin=dp(a,12);
            base.addView(copy,copyLayout);
        }
        View rule=new View(a);rule.setBackgroundColor(0xff2b4150);
        LinearLayout.LayoutParams ruleLayout=new LinearLayout.LayoutParams(-1,dp(a,1));
        ruleLayout.bottomMargin=dp(a,9);base.addView(rule,ruleLayout);
        ScrollView scroll=new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        scroll.setFocusable(false);
        LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(a,2),dp(a,2),dp(a,2),dp(a,3));
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        base.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(base);
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.77f);
            int displayWidth=a.getResources().getDisplayMetrics().widthPixels;
            int displayHeight=a.getResources().getDisplayMetrics().heightPixels;
            int width=Math.min(dp(a,760),Math.max(dp(a,430),displayWidth-dp(a,62)));
            width=Math.min(width,displayWidth-dp(a,30));
            int height=Math.min(dp(a,610),displayHeight-dp(a,34));
            window.setLayout(width,Math.max(dp(a,235),height));
            window.setGravity(Gravity.CENTER);
        }
        Frame f=new Frame();f.dialog=dialog;f.content=list;
        return f;
    }
    private static View row(Activity a,Frame parent,String icon,String title,String detail,
                            boolean selected,Runnable onClick){
        LinearLayout card=new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(a,15),dp(a,8),dp(a,12),dp(a,8));
        card.setBackground(panel(a,selected?HIGHLIGHT:SURFACE,
            selected?ACCENT:BORDER,12));
        card.setFocusable(true);card.setClickable(true);
        card.setContentDescription(title+(detail.isEmpty()?"":". "+detail));
        if(icon!=null&&!icon.isEmpty()){
            TextView glyph=text(a,icon,19,ACCENT,true);
            glyph.setGravity(Gravity.CENTER);
            card.addView(glyph,new LinearLayout.LayoutParams(dp(a,37),dp(a,36)));
        }
        LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);
        TextView label=text(a,title,15,Color.WHITE,true);
        label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(label);
        if(detail!=null&&!detail.isEmpty()){
            TextView hint=text(a,detail,12,MUTED,false);
            hint.setSingleLine(true);hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams hintSize=new LinearLayout.LayoutParams(-1,-2);
            hintSize.topMargin=dp(a,4);
            copy.addView(hint,hintSize);
        }
        LinearLayout.LayoutParams copySize=new LinearLayout.LayoutParams(0,-2,1);
        copySize.leftMargin=dp(a,4);card.addView(copy,copySize);
        TextView arrow=text(a,selected?"✓":"›",20,selected?ACCENT:0xff7c9aa9,false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow,new LinearLayout.LayoutParams(dp(a,28),dp(a,34)));
        card.setOnFocusChangeListener((v,focused)->{
            card.setBackground(panel(a,focused?0xff25493f:
                (selected?HIGHLIGHT:SURFACE),focused?ACCENT:
                (selected?ACCENT:BORDER),12));
            label.setTextColor(focused?ACCENT:Color.WHITE);
            card.animate().scaleX(focused?1.012f:1f).scaleY(focused?1.012f:1f)
                .setDuration(100).start();
        });
        card.setOnClickListener(v->{
            parent.dialog.dismiss();
            onClick.run();
        });
        LinearLayout.LayoutParams pos=new LinearLayout.LayoutParams(-1,dp(a,63));
        pos.bottomMargin=dp(a,7);
        parent.content.addView(card,pos);
        return card;
    }
    private static void section(Activity a,Frame parent,String name){
        if(name==null||name.isEmpty())return;
        TextView title=text(a,name.toUpperCase(java.util.Locale.ROOT),11,ACCENT,true);
        title.setLetterSpacing(.12f);
        LinearLayout.LayoutParams pos=new LinearLayout.LayoutParams(-1,dp(a,31));
        pos.topMargin=dp(a,3);parent.content.addView(title,pos);
    }
    public static void menu(Activity a,String title,String description,Entry... actions){
        Frame frame=open(a,title,description);
        String last=null;View initial=null;
        for(Entry entry:actions){
            if(!entry.section.equals(last)){section(a,frame,entry.section);last=entry.section;}
            View v=row(a,frame,entry.icon,entry.title,
                entry.detail==null?"":entry.detail,false,entry.click);
            if(initial==null)initial=v;
        }
        if(initial!=null)initial.requestFocus();
    }
    public static void choices(Activity a,String title,String description,
                               String[] options,int selected,IntConsumer choose){
        Frame frame=open(a,title,description);
        View initial=null;
        for(int i=0;i<options.length;i++){
            final int index=i;
            View v=row(a,frame,i==selected?"●":"○",options[i],"",
                i==selected,()->choose.accept(index));
            if(i==selected||initial==null)initial=v;
        }
        if(initial!=null)initial.requestFocus();
    }
    public static void message(Activity a,String title,String message,
                               String actionLabel,Runnable action){
        Frame frame=open(a,title,"");
        TextView content=text(a,message,15,0xffddebf2,false);
        content.setGravity(Gravity.TOP|Gravity.LEFT);
        content.setLineSpacing(dp(a,6),1.05f);
        content.setPadding(dp(a,9),dp(a,13),dp(a,9),dp(a,18));
        frame.content.addView(content,new LinearLayout.LayoutParams(-1,-2));
        View control=row(a,frame,"✓",actionLabel,"",true,action);
        control.requestFocus();
    }
    public static void prompt(Activity a,String title,String details,String previous,
                              String hint,Consumer<String> save){
        Frame frame=open(a,title,details);
        EditText input=new EditText(a);
        input.setSingleLine(true);input.setText(previous==null?"":previous);
        input.setHint(hint);input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setPadding(dp(a,16),0,dp(a,16),0);
        input.setBackground(panel(a,0xff142b37,ACCENT,11));
        input.setInputType(InputType.TYPE_CLASS_TEXT|
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams area=new LinearLayout.LayoutParams(-1,dp(a,50));
        area.bottomMargin=dp(a,16);frame.content.addView(input,area);
        View saveRow=row(a,frame,"✓","Save changes","Store this setting on this device",
            true,()->save.accept(input.getText().toString().trim()));
        saveRow.requestFocus();
    }
}
