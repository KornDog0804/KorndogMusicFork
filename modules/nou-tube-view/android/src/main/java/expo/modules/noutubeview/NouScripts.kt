package expo.modules.noutubeview

val VIEW_HOSTS = arrayOf("youtube.com", "youtu.be")

/* ========================= */
/* 🎨 KORNDOG THEME */
/* ========================= */
val KORNDOG_THEME_CSS = """:root{
--yt-spec-base-background:#120020!important;
--yt-spec-raised-background:#1a0a2e!important;
--yt-spec-menu-background:#2d1450!important;
--yt-spec-brand-background-solid:#2d1450!important;
--yt-spec-general-background-a:#120020!important;
--yt-spec-general-background-b:#1a0a2e!important;
--yt-spec-general-background-c:#2d1450!important;
--yt-spec-call-to-action:#39ff14!important;
--yt-spec-static-brand-red:#39ff14!important;
--yt-spec-text-primary:#f0eaf8!important;
--yt-spec-text-secondary:#bba7d9!important;
--yt-spec-icon-active-other:#39ff14!important;
--yt-spec-brand-icon-active:#39ff14!important
}
ytmusic-nav-bar,#nav-bar-background,ytmusic-player-bar{background:#2d1450!important}
ytmusic-player-bar{border-top:3px solid #39ff14!important}
""".replace("'","\\'")

/* ========================= */
/* 🎧 QUEUE TRACKER */
/* ========================= */
val KORNDOG_QUEUE_TRACKER_SCRIPT = """
(function(){
if(window._kdQueueTrackerInit)return;
window._kdQueueTrackerInit=true;

var KEY='korndog_queue',MAX=5,last='',lastProg=0;

function media(){return document.querySelector('video,audio');}

function info(){
  var title=document.querySelector('.title')?.innerText||'';
  var artist=document.querySelector('.subtitle')?.innerText||'';
  var thumb=document.querySelector('img')?.src||'';
  var m=media();

  return{
    title:title.trim(),
    artist:artist.trim(),
    thumb:thumb,
    seconds:Math.floor(m?.duration||0)
  };
}

function send(i){
  if(!i.title||!i.artist)return;

  if(window.NouTubeI&&window.NouTubeI.notify){
    window.NouTubeI.notify(i.title,i.artist,i.seconds||0,i.thumb||'');
  }
}

function prog(){
  var m=media();
  if(!m)return;

  if(window.NouTubeI&&window.NouTubeI.notifyProgress){
    window.NouTubeI.notifyProgress(!m.paused,Math.floor(m.currentTime||0));
  }
}

function tick(){
  var i=info();
  var k=i.title+'|'+i.artist;

  if(k&&k!==last){
    last=k;

    var q=[];
    try{q=JSON.parse(localStorage.getItem(KEY)||'[]')}catch(e){}

    q.unshift(i);
    if(q.length>MAX)q=q.slice(0,MAX);

    localStorage.setItem(KEY,JSON.stringify(q));
    send(i);
  }

  prog();
}

setInterval(tick,1000);
})();
""".trimIndent()

/* ========================= */
/* 🔥 DUAL BUTTONS */
/* ========================= */
val KORNDOG_CAST_SCRIPT = """
(function(){

if(window.__korndogButtons)return;
window.__korndogButtons=true;

function latest(){
  try{
    var q=JSON.parse(localStorage.getItem('korndog_queue')||'[]');
    if(q.length)return q[0];
  }catch(e){}
  return {};
}

function go(type){
  var t=latest();

  var base = type==='stream'
    ? 'https://korndogrecords.com/korndog-streaming-generator.html'
    : 'https://korndogrecords.com/korndog-spinning-generator.html';

  var url = base +
    '?artist='+encodeURIComponent(t.artist||'')+
    '&title='+encodeURIComponent(t.title||'')+
    '&thumb='+encodeURIComponent(t.thumb||'');

  window.location.href=url;
}

function btn(id,emoji,bottom,color,type){
  if(document.getElementById(id))return;

  var b=document.createElement('button');
  b.id=id;
  b.innerText=emoji;

  b.style.cssText=
    'position:fixed;' +
    'right:14px;' +
    'bottom:'+bottom+'px;' +
    'z-index:999999;' +
    'width:42px;' +
    'height:42px;' +
    'border-radius:14px;' +
    'background:#12001c;' +
    'border:2px solid '+color+';' +
    'color:white;' +
    'font-size:20px;' +
    'box-shadow:0 0 12px '+color+';';

  b.onclick=function(){go(type)};
  document.body.appendChild(b);
}

setTimeout(function(){
  btn('kd-tv','📺',120,'#39ff14','vinyl');
  btn('kd-stream','🎧',170,'#b000ff','stream');
},1000);

})();
""".trimIndent()

/* ========================= */
/* 🎤 LYRICS */
/* ========================= */
val KORNDOG_SYNCED_LYRICS_SCRIPT = """
(function(){
if(window._kdLyricsHighlightInit)return;
window._kdLyricsHighlightInit=true;

var s=document.createElement('style');
s.textContent='.korndog-lyric-active{color:#39ff14!important;font-weight:bold!important}';
document.head.appendChild(s);
})();
""".trimIndent()
