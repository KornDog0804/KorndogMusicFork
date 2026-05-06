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

function cleanAlbum(t){
  return(t||'')
    .replace(/\s+/g,' ')
    .replace(/Explicit|Album|Song|Video/g,'')
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
    '.miniplayer img',
    'ytmusic-player-page img'
  ];

  for(var x=0;x<spots.length;x++){
    var im=document.querySelector(spots[x]);
    if(im){
      var src=im.currentSrc||im.src||'';
      if(src&&(src.indexOf('ytimg')>-1||src.indexOf('googleusercontent')>-1))return up(src);
    }
  }

  var og=document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');
  if(og&&og.content)return up(og.content);

  var a=q();
  return a.length&&a[0].thumb?a[0].thumb:'';
}

function albumFromDom(title,artist){
  var candidates=[];

  var selectors=[
    'ytmusic-player-page .subtitle',
    '.content-info-wrapper .subtitle',
    'ytmusic-player-bar .subtitle',
    'ytmusic-player-page ytmusic-description-shelf-renderer',
    'ytmusic-player-page .description',
    'yt-formatted-string.subtitle'
  ];

  for(var i=0;i<selectors.length;i++){
    var nodes=document.querySelectorAll(selectors[i]);
    for(var n=0;n<nodes.length;n++){
      var raw=(nodes[n].innerText||nodes[n].textContent||'').trim();
      if(raw)candidates.push(raw);
    }
  }

  for(var c=0;c<candidates.length;c++){
    var raw=candidates[c].replace(/\n/g,' • ').replace(/\s+/g,' ').trim();

    if(raw.indexOf(' • ')>-1){
      var parts=raw.split(' • ').map(function(p){return cleanAlbum(p)}).filter(Boolean);

      for(var p=0;p<parts.length;p++){
        var part=parts[p];

        if(!part)continue;
        if(title&&part.toLowerCase()===title.toLowerCase())continue;
        if(artist&&part.toLowerCase()===artist.toLowerCase())continue;
        if(/^[0-9]{4}$/.test(part))continue;
        if(/views|likes|songs?|videos?|playlist|radio/i.test(part))continue;

        if(p>0)return part;
      }
    }
  }

  var metaAlbum=document.querySelector('meta[itemprop="inAlbum"],meta[property="music:album"]');
  if(metaAlbum&&metaAlbum.content){
    var ma=cleanAlbum(metaAlbum.content);
    if(ma&&ma.toLowerCase()!==title.toLowerCase())return ma;
  }

  return '';
}

function info(){
  var title='',artist='',album='',seconds=0,m=media();

  if(m&&isFinite(m.duration))seconds=Math.floor(m.duration||0);

  var te=document.querySelector('ytmusic-player-bar .title')||
    document.querySelector('.content-info-wrapper .title')||
    document.querySelector('ytmusic-player-page .title');

  if(te)title=clean(te.innerText||te.textContent);

  var ae=document.querySelector('ytmusic-player-bar .subtitle')||
    document.querySelector('.content-info-wrapper .subtitle')||
    document.querySelector('ytmusic-player-page .subtitle');

  var rawArtist='';
  if(ae)rawArtist=clean(ae.innerText||ae.textContent);

  artist=rawArtist;

  if(artist.indexOf(' • ')>-1)artist=artist.split(' • ')[0].trim();
  if(artist.indexOf(' - ')>-1)artist=artist.split(' - ')[0].trim();

  album=albumFromDom(title,artist);

  if(album&&title&&album.toLowerCase()===title.toLowerCase())album='';
  if(album&&artist&&album.toLowerCase()===artist.toLowerCase())album='';

  return{
    title:title,
    artist:artist,
    album:album,
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
    if(i.thumb)a[0].thumb=i.thumb;
    if(i.album)a[0].album=i.album;

    if(!i.thumb&&a[0].thumb)i.thumb=a[0].thumb;
    if(!i.album&&a[0].album)i.album=a[0].album;

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

var STREAMING_BACKGROUND='https://korndogrecords.com/images/file_000000004368722fb87de060d4ab9f9f.png';

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

    if(song.album && song.album !== song.title){
      p.set('album',song.album);
      p.set('albumName',song.album);
      p.set('albumTitle',song.album);
    }

    if(song.thumb){
      p.set('thumb',song.thumb);
      p.set('art',song.thumb);
      p.set('albumArt',song.thumb);
      p.set('cover',song.thumb);
    }

    var base = type === 'stream'
      ? 'https://korndogrecords.com/korndog-streaming-generator.html'
      : 'https://korndogrecords.com/korndog-spinning-generator.html';

    window.location.href=base+'?'+p.toString();
  }catch(e){
    window.location.href='https://korndogrecords.com/korndog-spinning-generator.html?from=ghostkernel';
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
  button(
    'korndog-stream-btn',
    '🎧',
    'Open KornDog Streaming Generator',
    166,
    '#b000ff',
    'rgba(176,0,255,.55)',
    'stream'
  );

  button(
    'korndog-tv-btn',
    '📺',
    'Open KornDog Vinyl Generator',
    116,
    '#39ff14',
    'rgba(57,255,20,.45)',
    'vinyl'
  );
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
