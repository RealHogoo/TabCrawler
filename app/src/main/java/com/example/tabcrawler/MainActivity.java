package com.example.tabcrawler;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.*;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    WebView browser; EditText url, selector; TextView status; Db db; MiniServer server;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12,12,12,12);
        url = new EditText(this); url.setSingleLine(true); url.setText("https://example.com"); url.setHint("https://사이트주소"); root.addView(url);
        selector = new EditText(this); selector.setSingleLine(true); selector.setText("a"); selector.setHint("CSS 선택자 (예: .post a)"); root.addView(selector);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = new Button(this); open.setText("열기"); actions.addView(open);
        Button collect = new Button(this); collect.setText("수집"); actions.addView(collect);
        root.addView(actions);
        status = new TextView(this); status.setText("서버 시작 중..."); root.addView(status);
        browser = new WebView(this); browser.setBackgroundColor(Color.WHITE); root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        WebSettings ws=browser.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
        browser.setWebViewClient(new WebViewClient());
        open.setOnClickListener(v -> { String u=url.getText().toString().trim(); if(u.startsWith("https://")||u.startsWith("http://")) browser.loadUrl(u); else status.setText("http(s) URL을 입력하세요"); });
        collect.setOnClickListener(v -> collect());
        Executors.newSingleThreadExecutor().execute(() -> { try {server=new MiniServer(db); server.start(); runOnUiThread(()->status.setText("서버 실행: http://태블릿IP:8080 (같은 Wi-Fi에서 접속)"));} catch(Exception e){runOnUiThread(()->status.setText("서버 실패: "+e.getMessage()));} });
        browser.loadUrl("https://example.com");
    }
    void collect(){
        String css=selector.getText().toString().trim();
        String js="(function(){var s="+JSONObject.quote(css)+";try{return JSON.stringify(Array.from(document.querySelectorAll(s)).slice(0,200).map(function(e){var a=e.querySelector('a[href]');return {title:(e.innerText||e.textContent||'').trim().slice(0,500),url:e.href||(a&&a.href)||location.href};}));}catch(e){return JSON.stringify({error:String(e)});}})()";
        browser.evaluateJavascript(js, value -> {
            try {
                JSONArray arr = new JSONArray(new org.json.JSONTokener(value).nextValue().toString());
                int n=0; String source=browser.getUrl();
                for(int i=0;i<arr.length();i++){JSONObject x=arr.getJSONObject(i);String title=x.optString("title");String link=x.optString("url");if(!title.isEmpty()){db.insert(source, title, link);n++;}}
                status.setText("수집 완료: "+n+"개 추출 (중복 자동 제외)");
            } catch(Exception e){status.setText("수집 오류: 선택자와 페이지 로딩 상태 확인 - "+e.getMessage());}
        });
    }
    @Override protected void onDestroy(){if(server!=null)server.stop();browser.destroy();db.close();super.onDestroy();}
    static class Db extends SQLiteOpenHelper {
        Db(Context c){super(c,"posts.db",null,1);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE posts(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT,title TEXT,url TEXT,created_at INTEGER,UNIQUE(source,url,title))");}
        @Override public void onUpgrade(SQLiteDatabase d,int o,int n){}
        synchronized void insert(String source,String title,String url){android.content.ContentValues v=new android.content.ContentValues();v.put("source",source);v.put("title",title);v.put("url",url);v.put("created_at",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("posts",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
        synchronized JSONArray list(){JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT title,url,source,created_at FROM posts ORDER BY id DESC LIMIT 200",null)){while(c.moveToNext()){JSONObject x=new JSONObject();x.put("title",c.getString(0));x.put("url",c.getString(1));x.put("source",c.getString(2));x.put("created_at",c.getLong(3));a.put(x);}}catch(Exception ignored){}return a;}
    }
    static class MiniServer {
        final Db db; volatile boolean running; ServerSocket sock;
        MiniServer(Db db){this.db=db;}
        void start() throws IOException {sock=new ServerSocket();sock.setReuseAddress(true);sock.bind(new InetSocketAddress("0.0.0.0",8080));running=true;Executors.newSingleThreadExecutor().execute(()->{while(running){try{Socket c=sock.accept();Executors.newSingleThreadExecutor().execute(()->serve(c));}catch(IOException e){if(running)android.util.Log.e("TabCrawler","accept",e);}}});}
        void serve(Socket s){try(Socket c=s){c.setSoTimeout(5000);BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));String request=r.readLine();if(request==null)return;String line;while((line=r.readLine())!=null&&!line.isEmpty()){}String path=request.split(" ")[1];String body;String type;
            if(path.equals("/api/posts")){body=db.list().toString();type="application/json";}
            else if(path.equals("/")){body="<!doctype html><html lang='ko'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>TabCrawler</title><style>body{font-family:sans-serif;max-width:720px;margin:25px auto;padding:15px}article{padding:12px;border-bottom:1px solid #ddd}a{color:#175caa}</style><h2>TabCrawler 수집 결과</h2><div id='list'>불러오는 중...</div><script>fetch('/api/posts').then(r=>r.json()).then(a=>{document.getElementById('list').innerHTML='';a.forEach(p=>{let x=document.createElement('article'),l=document.createElement('a');l.href=p.url;l.textContent=p.title;l.rel='noopener noreferrer';l.target='_blank';x.appendChild(l);document.getElementById('list').appendChild(x)});if(!a.length)document.getElementById('list').textContent='아직 수집된 글이 없습니다.'})</script></html>";type="text/html";}
            else {body="Not found";type="text/plain";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);String head="HTTP/1.1 "+(path.equals("/")||path.equals("/api/posts")?"200 OK":"404 Not Found")+"\r\nContent-Type: "+type+"; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";OutputStream out=c.getOutputStream();out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(Exception e){android.util.Log.e("TabCrawler","client",e);}}
        void stop(){running=false;try{sock.close();}catch(Exception ignored){}}
    }
}
