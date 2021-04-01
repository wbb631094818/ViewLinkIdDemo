package com.apt.viewlinkiddemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.apt.viewlink.ViewLink;

public class MainActivity extends BaseActivity {

    @ViewLink(R.id.textview)
    public TextView textview;

    @ViewLink(R.id.bt)
    public Button bt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewLinkUtil.link(this);

        textview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //
                Toast.makeText(MainActivity.this,"点击了text",Toast.LENGTH_SHORT).show();
                textview.setText("textview 点击了");
            }
        });

        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                textview.setText("button 点击了");
            }
        });
    }
}