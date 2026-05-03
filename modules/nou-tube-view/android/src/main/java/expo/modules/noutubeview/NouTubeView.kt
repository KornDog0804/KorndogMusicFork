package expo.modules.noutubeview

import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.*
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

val VIEW_HOSTS=arrayOf("youtube.com","youtu.be")
val KORNDOG_THEME_CSS=""":root{--yt-spec-base-background:#120020!important;--yt-spec-raised-background:#1a0a2e!important;--yt-spec-menu-background:#2d1450!important;--yt-spec-brand-background-solid:#2d1450!important;--yt-spec-general-background-a:#120020!important;--yt-spec-general-background-b:#1a0a2e!important;--yt-spec-general-background-c:#2d1450!important;--yt-spec-call-to-action:#39ff14!important;--yt-spec-static-brand-red:#39ff14!important;--yt-spec-text-primary:#f0eaf8!important;--yt-spec-text-secondary:#bba7d9!important;--yt-spec-icon-active-other:#39ff14!important;--yt-spec-brand-icon-active:#39ff14!important}ytmusic-nav-bar,#nav-bar-background,ytmusic-player-bar{background:#2d1450!important}ytmusic-player-bar{border-top:3px solid #39ff14!important;box-shadow:0 -2px 18px rgba(57,255,20,.18)!important}ytmusic-player-bar .title,.content-info-wrapper .title,ytmusic-player-page .title{color:#39ff14!important}ytmusic-player-bar .subtitle,.content-info-wrapper .subtitle,ytmusic-player-page .subtitle{color:#bba7d9!important}tp-yt-paper-slider #progressContainer #primaryProgress{background:#39ff14!important}ytmusic-chip-cloud-chip-renderer,ytmusic-responsive-list-item-renderer:hover,tp-yt-paper-listbox,ytmusic-menu-popup-renderer{background:#2d1450!important}ytmusic-player-page,ytmusic-browse-response,ytmusic-section-list-renderer{background:#120020!important;color:#f0eaf8!important}""".replace("'","\\'")

val KORNDOG_QUEUE_TRACKER_SCRIPT="""
(function(){if(window._kdQueueTrackerInit)return;window._kdQueueTrackerInit=true;
var KEY='korndog_queue',MAX=5,last='',lastProg=0;
function clean(t){return(t||'').replace(/\s+/g,' ').replace(/Explicit|Album|Song|Video/g,'').trim()}
function up(u){if(!u)return'';return u.replace(/=w[0-9]+-h[0-9]+.*$/i,'=w800-h800-l90-rj').replace(/\/s[0-9]+\//i,'/s800/')}
function q(){try{var s=localStorage.getItem(KEY);return s?JSON.parse(s):[]}catch(e){return[]}}
function save(a){try{localStorage.setItem(KEY,JSON.stringify(a))}catch(e){}}
function thumb(){var spots=['ytmusic-player-bar img','ytmusic-player img','.player-bar img','.miniplayer img'];for(var x=0;x<spots.length;x++){var im=document.querySelector(spots[x]);if(im){var src=im.currentSrc||im.src||'';if(src&&(src.indexOf('ytimg')>-1||src.indexOf('googleusercontent')>-1))return up(src)}}var title=(document.querySelector('ytmusic-player-bar .title')||document.querySelector('.content-info-wrapper .title')||{}).innerText||'';var rows=Array.from(document.querySelectorAll('ytmusic-responsive-list-item-renderer,tp-yt-paper-item,ytmusic-player-queue-item'));for(var r=0;r<rows.length;r++){var txt=(rows[r].innerText||'');if(title&&txt.indexOf(title)>-1){var img=rows[r].querySelector('img');if(img){var s=img.currentSrc||img.src||'';if(s)return up(s)}}}var imgs=Array.from(document.querySelectorAll('ytmusic-player-bar img,img')).filter(function(i){var s=i.currentSrc||i.src||'';if(!s)return false;if(s.indexOf('ytimg')<0&&s.indexOf('googleusercontent')<0)return false;var r=i.getBoundingClientRect();return r.width>=24&&r.height>=24&&r.width<500&&r.height<500}).sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return Math.abs((ar.width*ar.height)-4096)-Math.abs((br.width*br.height)-4096)});if(imgs.length)return up(imgs[0].currentSrc||imgs[0].src);var og=document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');if(og&&og.content)return up(og.content);var a=q();return a.length&&a[0].thumb?a[0].thumb:''}
function info(){var title='',artist='',seconds=0,m=document.querySelector('video,audio');if(m)seconds=Math.floor(m.duration||0);var te=document.querySelector('ytmusic-player-bar .title')||document.querySelector('.content-info-wrapper .title')||document.querySelector('ytmusic-player-page .title');if(te)title=clean(te.innerText||te.textContent);var ae=document.querySelector('ytmusic-player-bar .subtitle')||document.querySelector('.content-info-wrapper .subtitle')||document.querySelector('ytmusic-player-page .subtitle');if(ae)artist=clean(ae.innerText||ae.textContent);if(artist.indexOf(' • ')>-1)artist=artist.split(' • ')[0].trim();if(artist.indexOf(' - ')>-1)artist=artist.split(' - ')[0].trim();var ot=document.querySelector('meta[property="og:title"]');if(!title&&ot&&ot.content)title=clean(ot.content);var od=document.querySelector('meta[property="og:description"]');if(!artist&&od&&od.content)artist=clean(od.content);return{title:title,artist:artist,thumb:thumb(),seconds:seconds}}
function send(i){try{if(!i.title||!i.artist)return;if(!i.thumb){var a=q();if(a.length&&a[0].thumb)i.thumb=a[0].thumb}if(window.NouTubeI&&window.NouTubeI.notify)window.NouTubeI.notify(i.title,i.artist,String(i.seconds||0),i.thumb||'')}catch(e){}}
function prog(){try{var m=document.querySelector('video,audio');if(!m)return;var n=Date.now();if(n-lastProg<1000)return;lastProg=n;if(window.NouTubeI&&window.NouTubeI.notifyProgress)window.NouTubeI.notifyProgress(String(!m.paused),String(Math.floor(m.currentTime||0)))}catch(e){}}
function add(i){if(!i.title||!i.artist)return;var a=q();if(a.length&&a[0].title===i.title&&a[0].artist===i.artist){if(i.thumb){a[0].thumb=i.thumb;save(a)}else if(a[0].thumb)i.thumb=a[0].thumb;send(i);return}a.unshift({title:i.title,artist:i.artist,thumb:i.thumb||'',played:Date.now()});if(a.length>MAX)a=a.slice(0,MAX);save(a);send(i)}
function tick(){try{var i=info(),k=i.title+'|'+i.artist;if(k&&i.title&&i.artist){if(k!==last){last=k;add(i)}else add(i)}prog()}catch(e){}}
setInterval(tick,1500);document.addEventListener('play',function(){setTimeout(tick,250)},true);document.addEventListener('pause',function(){setTimeout(tick,250)},true);document.addEventListener('timeupdate',tick,true);setTimeout(tick,500
