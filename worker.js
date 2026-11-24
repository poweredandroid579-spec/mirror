import { DurableObject } from "cloudflare:workers";

// Mirror: servidor
// /       página para você controlar o telefone (no navegador)
// /start  página para abrir o app no telefone de casa
// /ws     ponte (WebSocket) entre o telefone e o navegador
// O vídeo da tela NÃO passa por aqui: vai direto entre os aparelhos.
// A senha fica guardada na Cloudflare (MIRROR_PASSWORD), nunca no código.

const RELAY_TYPES = ["request", "offer", "answer", "ice"];

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Use websocket", { status: 426 });
    }
    const role = new URL(request.url).searchParams.get("role") === "phone" ? "phone" : "viewer";

    for (const old of this.ctx.getWebSockets(role)) {
      try { old.close(1000, "replaced"); } catch (e) {}
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [role]);

    if (role === "phone") {
      this.sendTo("viewer", { type: "status", phone: true });
      server.send(JSON.stringify({ type: "status", phone: true }));
    } else {
      const phoneOnline = this.ctx.getWebSockets("phone").some((s) => s.readyState === 1);
      server.send(JSON.stringify({ type: "status", phone: phoneOnline }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  sendTo(tag, obj) {
    const text = JSON.stringify(obj);
    for (const s of this.ctx.getWebSockets(tag)) {
      if (s.readyState === 1) {
        try { s.send(text); } catch (e) {}
      }
    }
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 65536) return;
    let data;
    try { data = JSON.parse(message); } catch (e) { return; }
    if (!data || RELAY_TYPES.indexOf(data.type) === -1) return;
    const role = this.ctx.getTags(ws)[0];
    const other = role === "phone" ? "viewer" : "phone";
    this.sendTo(other, data);
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch (e) {}
    this.afterLeave(ws);
  }

  async webSocketError(ws, error) {
    this.afterLeave(ws);
  }

  afterLeave(ws) {
    const role = this.ctx.getTags(ws)[0];
    if (role === "phone") {
      const stillThere = this.ctx.getWebSockets("phone").some((s) => s !== ws && s.readyState === 1);
      if (!stillThere) this.sendTo("viewer", { type: "status", phone: false });
    }
  }
}

function safeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}

function html(body) {
  return new Response(body, {
    headers: {
      "content-type": "text/html;charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/ws") {
      const pw = url.searchParams.get("pw") || "";
      if (!env.MIRROR_PASSWORD || !safeEqual(pw, env.MIRROR_PASSWORD)) {
        return new Response("Unauthorized", { status: 401 });
      }
      const room = env.ROOM.get(env.ROOM.idFromName("main"));
      return room.fetch(request);
    }

    if (url.pathname === "/start") return html(START_PAGE);
    if (url.pathname === "/") return html(VIEWER_PAGE);
    return new Response("Not found", { status: 404 });
  },
};

const START_PAGE = `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#12161c">
<title>Mirror: iniciar</title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:28px;padding:28px;background:#12161c;color:#e8edf3;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;text-align:center}
h1{margin:0;font-size:30px;font-weight:600;letter-spacing:.01em}
a.btn{display:block;padding:30px 44px;border-radius:22px;background:#5aa9ff;color:#06182c;font-size:28px;font-weight:700;text-decoration:none}
p{margin:0;font-size:19px;line-height:1.5;color:#9aa7b6;max-width:320px}
</style>
</head>
<body>
<h1>Mirror</h1>
<a class="btn" href="mirror://start">Abrir o app</a>
<p>Quando o app abrir, toque em <b>Iniciar agora</b> se o Android perguntar.</p>
</body>
</html>`;

const VIEWER_PAGE = `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#12161c">
<title>Mirror</title>
<style>
*{box-sizing:border-box}
html,body{margin:0;height:100%;background:#12161c;color:#e8edf3;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
#login{display:flex;flex-direction:column;gap:14px;max-width:360px;margin:0 auto;padding:20vh 24px 24px}
h1{margin:0 0 4px;font-size:30px;font-weight:600}
input,button{font:inherit;border-radius:12px;border:1px solid #2b3542;background:#1b222b;color:inherit;padding:14px}
button{cursor:pointer}
button:active{background:#243040}
button.primary{background:#5aa9ff;border-color:#5aa9ff;color:#06182c;font-weight:700}
#msg{min-height:1.3em;margin:0;color:#ff8a80}
#app{display:flex;flex-direction:column;height:100%}
#status{padding:8px 12px;font-size:14px;color:#9aa7b6;text-align:center;min-height:34px}
#stage{flex:1;min-height:0;background:#000}
#v{display:block;width:100%;height:100%;object-fit:contain;touch-action:none;user-select:none;-webkit-user-select:none}
#bar{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;padding:8px}
#bar button{padding:11px 4px;font-size:14px}
#typebar{display:flex;gap:6px;padding:0 8px;padding-bottom:max(8px,env(safe-area-inset-bottom))}
#typebar input{flex:1;min-width:0}
[hidden]{display:none!important}
</style>
</head>
<body>
<div id="login">
  <h1>Mirror</h1>
  <input id="pw" type="password" placeholder="Senha" autocomplete="current-password">
  <button id="go" class="primary">Entrar</button>
  <p id="msg"></p>
</div>
<div id="app" hidden>
  <div id="status"></div>
  <div id="stage"><video id="v" autoplay playsinline muted></video></div>
  <div id="bar">
    <button data-k="back">Voltar</button>
    <button data-k="home">Início</button>
    <button data-k="recents">Recentes</button>
    <button data-k="notifs">Avisos</button>
    <button data-k="wake">Acordar tela</button>
    <button data-k="lock">Bloquear</button>
  </div>
  <div id="typebar">
    <input id="txt" placeholder="Digite aqui" autocomplete="off">
    <button id="send">Enviar</button>
    <button id="del">Apagar</button>
  </div>
</div>
<script>
(function(){
var $=function(i){return document.getElementById(i)};
var ws=null,pc=null,dc=null,opened=false,timer=null,pinger=null;
var pw=localStorage.getItem("mirror_pw")||"";

function setStatus(t){$("status").textContent=t||""}
function sendWs(o){if(ws&&ws.readyState===1)ws.send(JSON.stringify(o))}
function sendCtl(o){if(dc&&dc.readyState==="open")dc.send(JSON.stringify(o))}
function closePc(){
  if(pc){try{pc.close()}catch(e){}pc=null}
  dc=null;$("v").srcObject=null;
}

function makePc(){
  pc=new RTCPeerConnection({iceServers:[{urls:["stun:stun.cloudflare.com:3478","stun:stun.l.google.com:19302"]}]});
  pc.ontrack=function(e){$("v").srcObject=e.streams[0];setStatus("");var p=$("v").play();if(p&&p.catch)p.catch(function(){})};
  pc.ondatachannel=function(e){dc=e.channel};
  pc.onicecandidate=function(e){if(e.candidate)sendWs({type:"ice",candidate:e.candidate})};
  pc.onconnectionstatechange=function(){
    if(!pc)return;
    if(pc.connectionState==="failed"){setStatus("A conexão falhou. Tentando de novo...");closePc();setTimeout(function(){sendWs({type:"request"})},2000)}
    else if(pc.connectionState==="disconnected"){setStatus("Conexão instável...")}
  };
}

function onMsg(m){
  if(m.type==="status"){
    if(m.phone){setStatus("Telefone online. Abrindo a tela...");sendWs({type:"request"})}
    else{closePc();setStatus("Telefone offline. Abra o app nele para começar.")}
  }else if(m.type==="offer"){
    closePc();makePc();
    pc.setRemoteDescription({type:"offer",sdp:m.sdp})
      .then(function(){return pc.createAnswer()})
      .then(function(a){return pc.setLocalDescription(a).then(function(){sendWs({type:"answer",sdp:a.sdp})})})
      .catch(function(e){setStatus("Erro: "+e)});
  }else if(m.type==="ice"&&pc){
    pc.addIceCandidate(m.candidate).catch(function(){});
  }
}

function connect(){
  clearTimeout(timer);opened=false;
  var proto=location.protocol==="https:"?"wss:":"ws:";
  ws=new WebSocket(proto+"//"+location.host+"/ws?role=viewer&pw="+encodeURIComponent(pw));
  ws.onopen=function(){
    opened=true;
    $("login").hidden=true;$("app").hidden=false;
    setStatus("Conectando ao telefone...");
    localStorage.setItem("mirror_pw",pw);
    clearInterval(pinger);
    pinger=setInterval(function(){if(ws&&ws.readyState===1)ws.send("ping")},25000);
  };
  ws.onmessage=function(e){var m;try{m=JSON.parse(e.data)}catch(x){return}onMsg(m)};
  ws.onclose=function(){
    clearInterval(pinger);closePc();
    if(!opened){
      $("login").hidden=false;$("app").hidden=true;
      $("msg").textContent="Senha incorreta ou servidor indisponível.";
      localStorage.removeItem("mirror_pw");
      return;
    }
    setStatus("Reconectando...");
    timer=setTimeout(connect,3000);
  };
}

var v=$("v"),down=null;
function norm(cx,cy){
  var r=v.getBoundingClientRect(),vw=v.videoWidth,vh=v.videoHeight;
  if(!vw||!vh)return null;
  var s=Math.min(r.width/vw,r.height/vh),w=vw*s,h=vh*s;
  var ox=r.left+(r.width-w)/2,oy=r.top+(r.height-h)/2;
  return {x:Math.min(1,Math.max(0,(cx-ox)/w)),y:Math.min(1,Math.max(0,(cy-oy)/h))};
}
v.addEventListener("pointerdown",function(e){
  e.preventDefault();
  try{v.setPointerCapture(e.pointerId)}catch(x){}
  down={x:e.clientX,y:e.clientY,t:Date.now()};
});
v.addEventListener("pointerup",function(e){
  if(!down)return;
  var a=norm(down.x,down.y),b=norm(e.clientX,e.clientY),dur=Date.now()-down.t;
  var dist=Math.hypot(e.clientX-down.x,e.clientY-down.y);
  down=null;
  if(!a||!b)return;
  if(dist<10){sendCtl({t:dur>500?"long":"tap",x:a.x,y:a.y})}
  else{sendCtl({t:"swipe",x1:a.x,y1:a.y,x2:b.x,y2:b.y,d:Math.min(1500,Math.max(120,dur))})}
});
v.addEventListener("pointercancel",function(){down=null});

var btns=document.querySelectorAll("#bar button");
for(var i=0;i<btns.length;i++){
  btns[i].addEventListener("click",function(){sendCtl({t:"key",k:this.getAttribute("data-k")})});
}
function sendText(){var t=$("txt").value;if(!t)return;sendCtl({t:"text",s:t});$("txt").value=""}
$("send").addEventListener("click",sendText);
$("txt").addEventListener("keydown",function(e){if(e.key==="Enter")sendText()});
$("del").addEventListener("click",function(){sendCtl({t:"del"})});

function login(){
  var p=$("pw").value;
  if(!p)return;
  pw=p;$("msg").textContent="";connect();
}
$("go").addEventListener("click",login);
$("pw").addEventListener("keydown",function(e){if(e.key==="Enter")login()});
if(pw)connect();
})();
</script>
</body>
</html>`;
