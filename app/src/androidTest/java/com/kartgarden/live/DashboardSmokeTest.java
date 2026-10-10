package com.kartgarden.live;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DashboardSmokeTest {
    private Button button(View v,String label) {
        if(v instanceof Button && label.contentEquals(((Button)v).getText()))return (Button)v;
        if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){Button found=button(g.getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    @Test public void opensDisarmedAndControlsFit() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{View root=a.getWindow().getDecorView();for(String label:new String[]{"Connect","Setup","Arm","STOP","Record CSV","Export","Details"}){Button b=button(root,label);assertNotNull(label,b);Rect r=new Rect();assertTrue(label+" visible",b.getGlobalVisibleRect(r));assertTrue(label+" fits viewport",r.width()>=b.getWidth()-1);}
                assertNotNull(button(root,"A · Arm"));assertNotNull(button(root,"B · Stop"));
                // No network/arm action occurs in this smoke test.
            });
            screenshot("dashboard.png");
            scenario.onActivity(a->button(a.getWindow().getDecorView(),"Setup").performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();screenshot("setup.png");
        }
    }
    @Test public void fullscreenRetainsStopAndReturns() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{View root=a.getWindow().getDecorView();button(root,"View").performClick();assertEquals(View.GONE,button(root,"Setup").getParent() instanceof View?((View)button(root,"Setup").getParent()).getVisibility():View.VISIBLE);assertNotNull(button(root,"B · Stop"));button(root,"B · Stop").performClick();button(root,"View").performClick();assertEquals(View.VISIBLE,((View)button(root,"Setup").getParent()).getVisibility());});
        }
    }
    private void screenshot(String name) throws Exception {
        Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        File dir=InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("screenshots");assertNotNull(dir);if(!dir.exists())assertTrue(dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();
    }
}
