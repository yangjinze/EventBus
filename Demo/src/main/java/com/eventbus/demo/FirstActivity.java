package com.eventbus.demo;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.eventbus.demo.been.BaseActivity;
import com.eventbus.demo.been.Boy;
import com.eventbus.demo.been.Man;
import com.eventbus.demo.been.Person;
import com.eventbus.demo.been.Play;
import com.eventbus.demo.been.Think;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class FirstActivity extends BaseActivity {
    String TAG = this.getClass().getSimpleName();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first);
        EventBus.getDefault().register(this);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
    @Subscribe(threadMode = ThreadMode.MAIN, priority = 10, sticky = false)
    public void receiveMsg1(String msg) {
        Log.d(TAG, "receiveMsg1 msg = " + msg + ",thread = " + Thread.currentThread());
    }
//
//    @Subscribe(threadMode = ThreadMode.BACKGROUND, priority = 20, sticky = true)
//    public void receiveMsg2(String msg) {
//        Log.d(TAG, "receiveMsg2 msg = " + msg + ",thread = " + Thread.currentThread());
//    }
//    @Subscribe(threadMode = ThreadMode.BACKGROUND, priority = 20, sticky = true)
//    public void receiveInt(int type) {
//        Log.d(TAG, "receiveInt type = " + type + ",thread = " + Thread.currentThread());
//    }
    @Subscribe()
    public void subsObject(Object obj) {
        Log.d(TAG, "subsObject obj = " + obj);
    }

    @Subscribe()
    public void subsPerson(Person person) {
        Log.d(TAG, "subsPerson person = " + person.name);
    }

    @Subscribe()
    public void subsThink(Think think) {
        Log.d(TAG, "subsThink");
        think.think();
    }

    @Subscribe()
    public void subsMan(Man man) {
        Log.d(TAG, "subsMan man = " + man.name + "," + man.sex + "," + man.childsCount);
    }

    @Subscribe()
    public void subsBoy(Boy boy) {
        Log.d(TAG, "subsBoy boy = " + boy.name + "," + boy.sex);
    }

    //post(new Man("小王", 2)), priority也无法使该方法比subsBoy或者subsMan更早执行
    //事件class > 接口 > 父类 > 父类接口 > 父类的父类 > 父类的父类接口.....
    @Subscribe(priority = 10000)
    public void subsPlay(Play play) {
        Log.d(TAG, "subsPlay");
        play.play();
    }

    public void goToSecondActivity(View view) {
        Intent intent = new Intent(this, SecondActivity.class);
        this.startActivity(intent);
    }
}
