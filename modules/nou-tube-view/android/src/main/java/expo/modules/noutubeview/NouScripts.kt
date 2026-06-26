package expo.modules.noutubeview

val VIEW_HOSTS = arrayOf("youtube.com", "youtu.be", "music.youtube.com")

val KORNDOG_THEME_CSS = """:root{--yt-spec-base-background:#120020!important;--yt-spec-raised-background:#1a0a2e!important;--yt-spec-menu-background:#2d1450!important;--yt-spec-brand-background-solid:#2d1450!important;--yt-spec-general-background-a:#120020!important;--yt-spec-general-background-b:#1a0a2e!important;--yt-spec-general-background-c:#2d1450!important;--yt-spec-call-to-action:#39ff14!important;--yt-spec-static-brand-red:#39ff14!important;--yt-spec-text-primary:#f0eaf8!important;--yt-spec-text-secondary:#bba7d9!important;--yt-spec-icon-active-other:#39ff14!important;--yt-spec-brand-icon-active:#39ff14!important}ytmusic-nav-bar,#nav-bar-background,ytmusic-player-bar{background:#2d1450!important}ytmusic-player-bar{border-top:3px solid #39ff14!important;box-shadow:0 -2px 18px rgba(57,255,20,.18)!important}ytmusic-player-bar .title,.content-info-wrapper .title,ytmusic-player-page .title{color:#39ff14!important}ytmusic-player-bar .subtitle,.content-info-wrapper .subtitle,ytmusic-player-page .subtitle{color:#bba7d9!important}tp-yt-paper-slider #progressContainer #primaryProgress{background:#39ff14!important}ytmusic-chip-cloud-chip-renderer,ytmusic-responsive-list-item-renderer:hover,tp-yt-paper-listbox,ytmusic-menu-popup-renderer{background:#2d1450!important}ytmusic-player-page,ytmusic-browse-response,ytmusic-section-list-renderer{background:#120020!important;color:#f0eaf8!important}""".replace("'","\\'")

val KORNDOG_QUEUE_TRACKER_SCRIPT = """
(function(){
if(window._kdQueueTrackerInit)return;
window._kdQueueTrackerInit=true;

var KEY='korndog_queue',MAX=5,last='',lastProg=0;

function clean(t){
  return(t||'')
    .replace(/\s+/g,' ')
    .replace(/Explicit|Video/g,'')
    .trim();
}

function up(u){
  if(!u)return'';
  return u
    .replace(/=w[0-9]+-h[0-9]+.*$/i,'=w800-h800-l90-rj')
    .replace(/\/s[0-9]+\//i,'/s800/');
}

function q(){
  try{
    var s=localStorage.getItem(KEY);
    return s?JSON.parse(s):[];
  }catch(e){return[]}
}

function save(a){
  try{localStorage.setItem(KEY,JSON.stringify(a))}catch(e){}
}

function media(){
  return document.querySelector('video,audio');
}

function thumb(){
  var spots=[
    'ytmusic-player-bar img',
    'ytmusic-player img',
    '.player-bar img',
    '.miniplayer img'
  ];

  for(var x=0;x<spots.length;x++){
    var im=document.querySelector(spots[x]);
    if(im){
      var src=im.currentSrc||im.src||'';
      if(src&&(src.indexOf('ytimg')>-1||src.indexOf('googleusercontent')>-1))return up(src);
    }
  }

  var a=q();
  return a.length&&a[0].thumb?a[0].thumb:'';
}

function info(){
  var title='',artist='',albumGuess='',seconds=0,m=media();

  if(m&&isFinite(m.duration))seconds=Math.floor(m.duration||0);

  var te=
    document.querySelector('ytmusic-player-bar .title')||
    document.querySelector('.title.ytmusic-player-bar');

  if(te)title=clean(te.innerText||te.textContent);

  var ae=
    document.querySelector('ytmusic-player-bar .subtitle')||
    document.querySelector('.subtitle.ytmusic-player-bar');

  var rawSub='';
  if(ae)rawSub=clean(ae.innerText||ae.textContent);

  // YT Music subtitle formats:
  // "Artist • Album • Year"  or  "Song • Artist"
  var parts=rawSub.split(' • ').map(function(p){return p.trim();});
  artist=parts[0]||'';
  albumGuess='';
  if(parts.length>=2)albumGuess=parts[1];
  // If parts[1] looks like a bare year, it's not an album name
  if(albumGuess&&/^\d{4}$/.test(albumGuess))albumGuess='';

  if(artist.indexOf(' - ')>-1)artist=artist.split(' - ')[0].trim();

  return{
    title:title,
    artist:artist,
    album:albumGuess,
    thumb:thumb(),
    seconds:seconds
  };
}

function send(i){
  try{
    if(!i.title||!i.artist)return;

    if(!i.thumb){
      var a=q();
      if(a.length&&a[0].thumb)i.thumb=a[0].thumb;
    }

    if(window.NouTubeI&&window.NouTubeI.notify){
      window.NouTubeI.notify(i.title,i.artist,Math.floor(i.seconds||0),i.thumb||'');
    }
  }catch(e){}
}

function prog(){
  try{
    var m=media();
    if(!m)return;

    var n=Date.now();
    if(n-lastProg<750)return;
    lastProg=n;

    if(window.NouTubeI&&window.NouTubeI.notifyProgress){
      window.NouTubeI.notifyProgress(!m.paused,Math.floor(m.currentTime||0));
    }
  }catch(e){}
}

function add(i){
  if(!i.title||!i.artist)return;

  var a=q();

  if(a.length&&a[0].title===i.title&&a[0].artist===i.artist){
    if(i.thumb){
      a[0].thumb=i.thumb;
    }else if(a[0].thumb){
      i.thumb=a[0].thumb;
    }

    if(i.album)a[0].album=i.album;

    save(a);
    send(i);
    return;
  }

  a.unshift({
    title:i.title,
    artist:i.artist,
    album:i.album||'',
    thumb:i.thumb||'',
    played:Date.now()
  });

  if(a.length>MAX)a=a.slice(0,MAX);
  save(a);
  send(i);
}

function tick(){
  try{
    var i=info(),k=i.title+'|'+i.artist;

    if(k&&i.title&&i.artist){
      if(k!==last){
        last=k;
        add(i);
      }else{
        add(i);
      }
    }

    prog();
  }catch(e){}
}

setInterval(tick,1000);
document.addEventListener('play',function(){setTimeout(tick,150)},true);
document.addEventListener('pause',function(){setTimeout(tick,150)},true);
document.addEventListener('timeupdate',tick,true);
document.addEventListener('durationchange',tick,true);
document.addEventListener('loadedmetadata',tick,true);

setTimeout(tick,500);
setTimeout(tick,1500);
setTimeout(tick,3000);
setTimeout(tick,5000);
})();
""".trimIndent()

val KORNDOG_TAP_SMOOTHER_SCRIPT = """
(function(){
if(window._kdTapSmootherInit)return;
window._kdTapSmootherInit=true;

function isKorndogButton(el){
  return !!(el && el.closest && el.closest('#korndog-discovery-btn,#korndog-stream-btn,#korndog-tv-btn'));
}

function isControl(el){
  return !!(el && el.closest && el.closest('button,a,tp-yt-paper-icon-button,ytmusic-menu-renderer,input,textarea,select'));
}

function playableRow(el){
  if(!el||!el.closest)return null;
  return el.closest(
    'ytmusic-responsive-list-item-renderer,'+
    'ytmusic-two-row-item-renderer,'+
    'ytmusic-player-queue-item'
  );
}

function currentKey(){
  try{
    var t=document.querySelector('ytmusic-player-bar .title,.title.ytmusic-player-bar');
    var a=document.querySelector('ytmusic-player-bar .subtitle,.subtitle.ytmusic-player-bar');
    return ((t&&(t.innerText||t.textContent))||'')+'|'+((a&&(a.innerText||a.textContent))||'');
  }catch(e){return''}
}

function css(){
  var old=document.getElementById('korndog-tap-smoother-style');
  if(old)old.remove();

  var s=document.createElement('style');
  s.id='korndog-tap-smoother-style';
  s.textContent=
    'ytmusic-responsive-list-item-renderer,'+
    'ytmusic-two-row-item-renderer,'+
    'ytmusic-player-queue-item{'+
      'touch-action:manipulation!important;'+
      '-webkit-tap-highlight-color:rgba(57,255,20,.22)!important;'+
      'cursor:pointer!important;'+
    '}'+
    '#korndog-discovery-btn,#korndog-stream-btn,#korndog-tv-btn{'+
      'pointer-events:auto!important;'+
      'touch-action:manipulation!important;'+
    '}';
  document.head.appendChild(s);
}

document.addEventListener('pointerup',function(e){
  try{
    if(isKorndogButton(e.target))return;
    if(isControl(e.target))return;

    var row=playableRow(e.target);
    if(!row)return;

    var before=currentKey();

    setTimeout(function(){
      try{
        var after=currentKey();
        if(after&&before&&after!==before)return;

        var playTarget =
          row.querySelector('yt-formatted-string.title')||
          row.querySelector('.title')||
          row.querySelector('a')||
          row;

        if(playTarget) playTarget.click();
      }catch(err){}
    },220);
  }catch(err){}
},true);

css();
setInterval(css,3000);
})();
""".trimIndent()

val KORNDOG_CLICKABLE_PLAYER_SCRIPT = """
(function () {
  if (window.__korndogClickablePlayerInstalled) return;
  window.__korndogClickablePlayerInstalled = true;

  function cleanText(v) {
    return (v || "").replace(/\s+/g, " ").trim();
  }

  function firstText(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        var txt = cleanText(el.innerText || el.textContent || "");
        if (txt) return txt;
      }
    }
    return "";
  }

  function getTrackData() {
    var title = firstText([
      "ytmusic-player-bar .title",
      ".title.ytmusic-player-bar",
      "ytmusic-player-page .title",
      ".content-info-wrapper .title"
    ]);

    var artist = firstText([
      "ytmusic-player-bar .subtitle",
      ".subtitle.ytmusic-player-bar",
      "ytmusic-player-page .subtitle",
      ".content-info-wrapper .subtitle"
    ]);

    artist = cleanText(artist).split("•")[0].split(" - ")[0].trim();

    return { title: title, artist: artist };
  }

  function goArtist(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }

    var data = getTrackData();
    if (!data.artist) return false;

    location.href =
      "https://music.youtube.com/search?q=" +
      encodeURIComponent(data.artist);

    return false;
  }

  function goAlbumSong(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }

    var data = getTrackData();
    if (!data.title && !data.artist) return false;

    location.href =
      "https://music.youtube.com/search?q=" +
      encodeURIComponent(data.artist + " " + data.title + " album");

    return false;
  }

  function markClickable(el, type) {
    if (!el || el.dataset.korndogClickable === type) return;

    el.dataset.korndogClickable = type;
    el.style.cursor = "pointer";
    el.style.textDecoration = "underline";
    el.style.textUnderlineOffset = "3px";
    el.style.textDecorationThickness = "2px";
    el.style.textDecorationColor = type === "title" ? "#39ff14" : "#b000ff";

    el.onclick = type === "title" ? goAlbumSong : goArtist;
  }

  function wireClicks() {
    var titleSelectors = [
      "ytmusic-player-bar .title",
      ".title.ytmusic-player-bar",
      "ytmusic-player-page .title",
      ".content-info-wrapper .title"
    ];

    var artistSelectors = [
      "ytmusic-player-bar .subtitle",
      ".subtitle.ytmusic-player-bar",
      "ytmusic-player-page .subtitle",
      ".content-info-wrapper .subtitle"
    ];

    titleSelectors.forEach(function(sel) {
      document.querySelectorAll(sel).forEach(function(el) {
        markClickable(el, "title");
      });
    });

    artistSelectors.forEach(function(sel) {
      document.querySelectorAll(sel).forEach(function(el) {
        markClickable(el, "artist");
      });
    });
  }

  wireClicks();
  setInterval(wireClicks, 1000);
})();
"""

val KORNDOG_ARTIST_BRAIN_SCRIPT = """
(function(){
if(window._kdArtistBrainInit)return;
window._kdArtistBrainInit=true;

var BRAIN_KEY='korndog_artist_brain';
var MAX_RELATED=12;

function now(){return Date.now();}

function readJson(key,fallback){
  try{
    var s=localStorage.getItem(key);
    return s?JSON.parse(s):fallback;
  }catch(e){return fallback}
}

function writeJson(key,val){
  try{localStorage.setItem(key,JSON.stringify(val))}catch(e){}
}

function clean(v){
  return String(v||'')
    .replace(/\s+/g,' ')
    .replace(/\bExplicit\b/gi,'')
    .replace(/\bOfficial\b/gi,'')
    .replace(/\bVideo\b/gi,'')
    .replace(/\bAudio\b/gi,'')
    .replace(/\bVisualizer\b/gi,'')
    .trim();
}

function keyName(v){
  return clean(v)
    .toLowerCase()
    .replace(/&/g,'and')
    .replace(/[^a-z0-9]+/g,' ')
    .trim();
}

function isBadName(v,current){
  var s=clean(v);
  var k=keyName(s);
  var cur=keyName(current);

  if(!k)return true;
  if(cur&&k===cur)return true;
  if(s.length<2||s.length>44)return true;

  var bad=[
    'home','explore','library','upgrade','downloads','download','play','pause',
    'shuffle','mix','radio','start radio','songs','song','albums','album',
    'videos','video','artists','artist','podcasts','podcast','lyrics',
    'related','up next','listen again','quick picks','supermix','my supermix',
    'recommended','recommended for you','new releases','charts','moods and genres',
    'more','next','previous','open app','subscribe','subscribed','views',
    'monthly audience','playlist','playlists','youtube music','music',
    'top songs','singles','eps','appears on','fans might also like',
    'similar artists','related artists','about'
  ];

  if(bad.indexOf(k)>-1)return true;
  if(/\d+\s*(k|m|b)?\s*(views|plays|subscribers|monthly|audience)/i.test(s))return true;
  if(/^\d+:\d+$/.test(s))return true;
  if(/^#?\d+$/.test(s))return true;
  if(k.indexOf('playlist')>-1)return true;
  if(k.indexOf('official')>-1)return true;
  if(k.indexOf('lyrics')>-1)return true;

  return false;
}

function addUnique(arr,name,current){
  name=clean(name);
  if(isBadName(name,current))return arr;

  var k=keyName(name);
  for(var i=0;i<arr.length;i++){
    if(keyName(arr[i])===k)return arr;
  }

  arr.push(name);
  return arr;
}

function getBrain(){
  return readJson(BRAIN_KEY,{});
}

function saveBrain(brain){
  writeJson(BRAIN_KEY,brain);
}

function currentTrack(){
  var fromQueue=null;

  try{
    var q=JSON.parse(localStorage.getItem('korndog_queue')||'[]');
    if(q&&q.length)fromQueue=q[0];
  }catch(e){}

  var title=fromQueue&&fromQueue.title?fromQueue.title:'';
  var artist=fromQueue&&fromQueue.artist?fromQueue.artist:'';

  if(!title){
    var te=document.querySelector('ytmusic-player-bar .title')||
      document.querySelector('.title.ytmusic-player-bar');
    if(te)title=clean(te.innerText||te.textContent);
  }

  if(!artist){
    var ae=document.querySelector('ytmusic-player-bar .subtitle')||
      document.querySelector('.subtitle.ytmusic-player-bar');

    if(ae){
      artist=clean(ae.innerText||ae.textContent);
      if(artist.indexOf(' • ')>-1)artist=artist.split(' • ')[0].trim();
      if(artist.indexOf(' - ')>-1)artist=artist.split(' - ')[0].trim();
    }
  }

  return {title:clean(title),artist:clean(artist)};
}

function learnTrack(){
  var t=currentTrack();
  if(!t.artist)return;

  var brain=getBrain();
  var k=keyName(t.artist);

  if(!brain[k]){
    brain[k]={
      name:t.artist,
      related:[],
      tracks:[],
      learnedFrom:[],
      lastSeen:now(),
      confidence:1
    };
  }

  brain[k].name=t.artist;
  brain[k].lastSeen=now();

  if(t.title){
    var exists=false;
    for(var i=0;i<brain[k].tracks.length;i++){
      if(keyName(brain[k].tracks[i])===keyName(t.title))exists=true;
    }
    if(!exists)brain[k].tracks.unshift(t.title);
    if(brain[k].tracks.length>12)brain[k].tracks=brain[k].tracks.slice(0,12);
  }

  if(brain[k].learnedFrom.indexOf('player')===-1)brain[k].learnedFrom.push('player');

  saveBrain(brain);
}

function shelfHeaderText(el){
  var h=el.querySelector('yt-formatted-string.title, .title, h2, h3');
  return clean(h?h.innerText||h.textContent:'');
}

function collectLinksInside(el,currentArtist){
  var out=[];
  var nodes=el.querySelectorAll(
    'ytmusic-two-row-item-renderer, ytmusic-responsive-list-item-renderer, a[href*="channel"], a[href*="browse"]'
  );

  for(var i=0;i<nodes.length;i++){
    var n=nodes[i];

    var txt =
      n.getAttribute('title') ||
      n.querySelector('.title')?.innerText ||
      n.querySelector('yt-formatted-string.title')?.innerText ||
      n.innerText ||
      n.textContent ||
      '';

    txt=clean(txt);

    if(txt.indexOf('\n')>-1)txt=txt.split('\n')[0].trim();
    if(txt.indexOf(' • ')>-1)txt=txt.split(' • ')[0].trim();

    out=addUnique(out,txt,currentArtist);
    if(out.length>=MAX_RELATED)break;
  }

  return out;
}

function collectRelatedFromPage(currentArtist){
  var related=[];
  var shelves=document.querySelectorAll(
    'ytmusic-carousel-shelf-renderer, ytmusic-shelf-renderer, ytmusic-section-list-renderer'
  );

  for(var i=0;i<shelves.length;i++){
    var sh=shelves[i];
    var head=shelfHeaderText(sh).toLowerCase();

    var good =
      head.indexOf('fans might also like')>-1 ||
      head.indexOf('similar artists')>-1 ||
      head.indexOf('related artists')>-1;

    if(!good)continue;

    var found=collectLinksInside(sh,currentArtist);
    for(var x=0;x<found.length;x++){
      related=addUnique(related,found[x],currentArtist);
    }
  }

  return related.slice(0,MAX_RELATED);
}

function learnPage(){
  var t=currentTrack();
  var currentArtist=t.artist;

  if(!currentArtist)return;

  var related=collectRelatedFromPage(currentArtist);
  if(!related.length)return;

  var brain=getBrain();
  var k=keyName(currentArtist);

  if(!brain[k]){
    brain[k]={
      name:currentArtist,
      related:[],
      tracks:[],
      learnedFrom:[],
      lastSeen:now(),
      confidence:1
    };
  }

  for(var i=0;i<related.length;i++){
    brain[k].related=addUnique(brain[k].related,related[i],currentArtist);
  }

  if(brain[k].related.length>MAX_RELATED){
    brain[k].related=brain[k].related.slice(0,MAX_RELATED);
  }

  brain[k].lastSeen=now();
  brain[k].confidence=Math.min(10,(brain[k].confidence||1)+1);

  if(brain[k].learnedFrom.indexOf('artist-page')===-1){
    brain[k].learnedFrom.push('artist-page');
  }

  saveBrain(brain);
}

function relatedFor(artist){
  var brain=getBrain();
  var k=keyName(artist);

  if(brain[k]&&brain[k].related&&brain[k].related.length){
    return brain[k].related.slice(0,3).join(' • ');
  }

  return '';
}

window.KorndogArtistBrain={
  learnNow:function(){
    learnTrack();
    learnPage();
    return getBrain();
  },
  get:function(){return getBrain();},
  relatedFor:function(artist){return relatedFor(artist);}
};

function tick(){
  try{
    learnTrack();
    learnPage();
  }catch(e){}
}

setInterval(tick,2500);
setTimeout(tick,800);
setTimeout(tick,2200);
setTimeout(tick,5000);
})();
""".trimIndent()

val KORNDOG_CAST_SCRIPT = """
(function(){
function theme(){
  try{
    var s=document.getElementById('korndog-theme');
    if(s)s.remove();

    var st=document.createElement('style');
    st.id='korndog-theme';
    st.textContent='${KORNDOG_THEME_CSS}';
    document.head.appendChild(st);
  }catch(e){}
}

theme();
setInterval(theme,2500);

var STREAMING_BACKGROUND='https://korndogrecords.com/images/korndog-streaming-template-blank.png';

function latestTrack(){
  try{
    var q=JSON.parse(localStorage.getItem('korndog_queue')||'[]');
    if(q&&q.length)return q[0];
  }catch(e){}

  return {title:'',artist:'',album:'',thumb:''};
}

function openGen(type){
  try{
    var song=latestTrack();
    var p=new URLSearchParams();

    var sourceName = type === 'stream' ? 'YouTube Music' : 'GhostKernel';

    p.set('from','ghostkernel');
    p.set('source',sourceName);
    p.set('musicSource',sourceName);
    p.set('mode',type);

    if(type === 'stream'){
      p.set('background',STREAMING_BACKGROUND);
      p.set('bg',STREAMING_BACKGROUND);
      p.set('posterBg',STREAMING_BACKGROUND);
    }

    if(song.artist)p.set('artist',song.artist);

    if(song.title){
      p.set('title',song.title);
      p.set('track',song.title);
      p.set('song',song.title);
    }

    if(song.album){
      p.set('album',song.album);
    }

    if(song.thumb){
      p.set('thumb',song.thumb);
      p.set('art',song.thumb);
      p.set('albumArt',song.thumb);
      p.set('cover',song.thumb);
    }

    if(type === 'discovery'){
      var liveSuggestions='';

      try{
        if(window.KorndogArtistBrain){
          window.KorndogArtistBrain.learnNow();
          liveSuggestions=window.KorndogArtistBrain.relatedFor(song.artist);
        }
      }catch(e){}

      if(liveSuggestions){
        p.set('soundsLike',liveSuggestions);
        p.set('relatedArtists',liveSuggestions);
      }
    }

    var base = type === 'stream'
      ? 'https://korndogrecords.com/korndog-streaming-generator.html'
      : type === 'discovery'
        ? 'https://korndogrecords.com/artist-discovery-generator.html'
        : 'https://korndogrecords.com/korndog-spinning-generator.html';

    window.location.href=base+'?'+p.toString();
  }catch(e){
    window.location.href='https://korndogrecords.com/artist-discovery-generator.html?artist=ARTIST&title=SONG';
  }
}

function button(id,emoji,label,bottom,border,glow,type){
  if(!document.body){
    setTimeout(function(){button(id,emoji,label,bottom,border,glow,type)},500);
    return;
  }

  if(document.getElementById(id))return;

  var b=document.createElement('button');
  b.id=id;
  b.textContent=emoji;
  b.setAttribute('aria-label',label);

  b.style.cssText=
    'position:fixed;' +
    'right:14px;' +
    'bottom:'+bottom+'px;' +
    'z-index:999999;' +
    'width:42px;' +
    'height:42px;' +
    'border-radius:14px;' +
    'border:2px solid '+border+';' +
    'background:rgba(45,20,80,.88);' +
    'color:#fff;' +
    'font-size:21px;' +
    'display:flex;' +
    'align-items:center;' +
    'justify-content:center;' +
    'box-shadow:0 0 16px '+glow+';' +
    'backdrop-filter:blur(10px);' +
    'padding:0;' +
    'margin:0;' +
    'cursor:pointer;' +
    'touch-action:manipulation;';

  b.addEventListener('click',function(e){
    e.preventDefault();
    e.stopPropagation();
    openGen(type);
    return false;
  });

  document.body.appendChild(b);
}

function install(){
  button('korndog-discovery-btn','🔍','Open Zombie Kitty Artist Discovery',216,'#ff3eb5','rgba(255,62,181,.55)','discovery');
  button('korndog-stream-btn','🎧','Open KornDog Streaming Generator',166,'#b000ff','rgba(176,0,255,.55)','stream');
  button('korndog-tv-btn','📺','Open KornDog Vinyl Generator',116,'#39ff14','rgba(57,255,20,.45)','vinyl');
}

setTimeout(install,1000);
setInterval(install,3000);
})();
""".trimIndent()

val KORNDOG_SYNCED_LYRICS_SCRIPT = """
(function(){
if(window._kdLyricsHighlightInit)return;
window._kdLyricsHighlightInit=true;

function css(){
  var o=document.getElementById('korndog-lyrics-highlight-style');
  if(o)o.remove();

  var s=document.createElement('style');
  s.id='korndog-lyrics-highlight-style';
  s.textContent=`ytmusic-description-shelf-renderer,ytmusic-description-shelf-renderer *,ytmusic-player-section-list-renderer,ytmusic-player-section-list-renderer *,ytmusic-player-page [role="tabpanel"],ytmusic-player-page [role="tabpanel"] *,.lyrics,.lyrics *,.lyrics-wrapper,.lyrics-wrapper *{color:#d8ffd0!important}.korndog-lyric-line{display:inline-block!important;color:#d8ffd0!important;transition:color .2s ease,text-shadow .2s ease,transform .2s ease!important}.korndog-lyric-active{color:#39ff14!important;font-weight:900!important;text-shadow:0 0 12px rgba(57,255,20,.95),0 0 24px rgba(57,255,20,.55)!important;transform:scale(1.025)!important}`;
  document.head.appendChild(s);
}

css();
setInterval(css,3000);
})();
""".trimIndent()
