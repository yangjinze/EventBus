package com.eventbus.demo;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.eventbus.demo.been.Man;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class SecondActivity extends AppCompatActivity {

    String TAG = this.getClass().getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);
    }

    public void post(View view) {
        EventBus.getDefault().post("123");
       // EventBus.getDefault().post(new Person("小王"));
 //       EventBus.getDefault().post(new Man("小王", 2));
        EventBus.getDefault().postSticky("123456789");
    }

    public void register(View view) {
        Log.d(TAG, "register");
        EventBus.getDefault().register(this);
    }

    public void unRegister(View view) {
        Log.d(TAG, "unRegister");
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(sticky = true)
    public void subStick(String msg) {
        Log.d(TAG, "subStick msg = " + msg + ",thread = " + Thread.currentThread());
        //EventBus.getDefault().removeStickyEvent(msg);//粘性事件只能手动删除，否则每次注册都会执行
    }

    //粘性事件优先级对于刚register的时候是没用的
    //register后再次post/postSticky就会根据优先级来处理
    @Subscribe(sticky = true, priority = 10000)
    public void subStick2(String msg) {
        Log.d(TAG, "subStick2 msg = " + msg + ",thread = " + Thread.currentThread());
    }
}
