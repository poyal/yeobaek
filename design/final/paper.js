/* Local, offline design prototype. No access to device files. */
(() => {
  'use strict';
  const paths = {
    folder:'<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M3 9h18"/>',
    file:'<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
    book:'<path d="M12 5c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1Z"/><path d="M12 5v15"/>',
    bookmark:'<path d="M6 3h12v18l-6-4-6 4Z"/>',
    back:'<path d="m12 5-7 7 7 7M5 12h15"/>', arrow:'<path d="m10 6 6 6-6 6"/>',
    settings:'<path d="M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6"/>',
    clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
    refresh:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 6a8 8 0 0 1 13 2M18 18a8 8 0 0 1-13-2"/>',
    close:'<path d="m6 6 12 12M18 6 6 18"/>', more:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    check:'<path d="m5 12 4 4L19 6"/>',search:'<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    move:'<path d="M3 12h7m6 0h5M3 8v8M21 8v8"/><circle cx="13" cy="12" r="3"/>',
    lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>',
    broken:'<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M14 3v6h6M9 13l6 5M15 13l-6 5"/>',
    trash:'<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',
    signal:'<path d="M4 18v-3M9 18v-6M14 18V9M19 18V5"/>',wifi:'<path d="M3 9a14 14 0 0 1 18 0M6 12a9 9 0 0 1 12 0M9 15a4 4 0 0 1 6 0"/><circle cx="12" cy="18" r=".8"/>',
    battery:'<rect x="3" y="7" width="17" height="10" rx="2"/><path d="M22 10v4M6 10h10v4H6Z"/>'
  };
  const icon = name => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.file}</svg>`;
  const brand = className => window.YeobaekBrand.mark(className);
  const esc = x => String(x).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const btn = (label, action, cls='', attrs='') => `<button type="button" class="${cls}" data-action="${action}" ${attrs}>${label}</button>`;
  const ib = (name, label, action, attrs='') => btn(icon(name),action,'icon-button',`aria-label="${label}" title="${label}" ${attrs}`);
  const key='yeobaek-paper-final-v1';
  const defaults={appTheme:'system',folder:'Books / 소설',font:'serif',size:18,line:1.85,margin:24,paper:'follow'};
  let prefs={...defaults};
  try{prefs={...defaults,...JSON.parse(localStorage.getItem(key)||'{}')};}catch(_){}
  for(const [name,allowed] of Object.entries({appTheme:['system','light','dark'],font:['serif','sans'],paper:['follow','light','sepia','dark']}))if(!allowed.includes(prefs[name]))prefs[name]=defaults[name];
  for(const [name,min,max] of [['size',14,32],['line',1.3,2.1],['margin',16,40]])prefs[name]=Math.max(min,Math.min(max,Number(prefs[name])||defaults[name]));
  if(typeof prefs.folder!=='string')prefs.folder=defaults.folder;
  const queryMode=new URLSearchParams(location.search).get('theme');
  if(['light','dark'].includes(queryMode))prefs.appTheme=queryMode;
  const save=()=>{try{localStorage.setItem(key,JSON.stringify(prefs));}catch(_){}};
  const media=matchMedia('(prefers-color-scheme: dark)');
  const mode=()=>prefs.appTheme==='system'?(media.matches?'dark':'light'):prefs.appTheme;
  const sample=[
    '비가 그친 뒤의 골목은 평소보다 조금 넓어 보였다. 가게 앞에 놓인 작은 화분들에는 아직 물방울이 남아 있었다. 나는 서두르지 않고 그 사이를 걸었다.',
    '익숙한 길에도 처음 보는 장면이 있었다. 닫힌 창문에 비친 하늘, 누군가 반듯하게 접어 둔 우산, 빵집 문틈으로 흘러나오는 따뜻한 냄새 같은 것들.',
    '“오늘은 조금 늦어도 괜찮겠네.”',
    '누구에게 하는 말인지도 모른 채 작게 중얼거렸다. 주머니 속 휴대전화는 조용했고, 다음 모퉁이까지는 아직 걸어갈 시간이 남아 있었다.',
    '집으로 돌아오자 창가에 오후의 빛이 길게 놓여 있었다. 읽다 만 책을 펼쳤다. 마지막으로 접어 둔 문장 옆에 손끝을 가만히 올려놓았다.',
    '그 문장은 며칠 전과 똑같았지만, 읽는 마음은 조금 달랐다. 어떤 이야기는 끝을 향해 서둘러 가지 않아도 좋았다. 그저 잠시 머물 수 있다면 충분했다.',
    '창밖에서 다시 바람이 불었다. 젖은 잎이 서로 닿는 소리를 들으며, 나는 다음 문장으로 천천히 시선을 옮겼다.'
  ];
  const paragraphs=[...sample,...sample,...sample];
  const books=[{id:0,title:'비가 그친 오후',progress:42.3,when:'어제',size:'1.2 MB'},{id:1,title:'숲으로 가는 길',progress:0,when:'',size:'840 KB'},{id:2,title:'여름의 마지막 편지',progress:12.8,when:'3일 전',size:'2.4 MB'}];
  const marks=new Map([[0,[{index:4,text:sample[4]}]],[1,[]],[2,[]]]);
  const encodings=new Map();
  const hiddenRecords=new Set();
  const configs=[
    ['folder','폴더 서재','LIBRARY','시작 폴더와 읽던 책을 한곳에. 원본은 연결한 폴더에 있습니다.'],
    ['resume','이어서 읽기','RESUME','독서 중 종료한 뒤 새로 실행하면, 읽던 책으로 돌아갈지 묻습니다.'],
    ['reader','TXT 독서','READING','본문을 탭해 메뉴를 숨기거나 표시합니다. 진행률은 상단, 위치 조절은 하단 이동에서.'],
    ['settings','앱 설정','APP SETTINGS','시작 폴더와 앱 테마를 관리합니다. TXT 전용 옵션은 별도로 엽니다.'],
    ['options','TXT 읽기 설정','TXT OPTIONS','명조·고딕, 크기·간격·본문 색상. 앱 설정과 읽기 기록은 유지됩니다.'],
    ['recent','최근 읽기','READING HISTORY','최근 읽은 위치로 돌아갑니다. 기록 삭제는 원본 파일에 영향을 주지 않습니다.']
  ];
  const edgeConfigs={
    onboard:{label:'01 / FIRST OPEN',icon:'folder',title:'읽을 폴더를<br>정해 주세요.',copy:'책이 있는 폴더를 연결하면 다음에도 그 자리에서 시작합니다.',button:'시작 폴더 선택',action:'choose-folder'},
    empty:{label:'02 / EMPTY FOLDER',icon:'book',title:'아직 읽을 책이<br>없어요.',copy:'이 폴더에 TXT 파일을 넣거나 다른 폴더를 선택해 주세요.',button:'시작 폴더 변경',action:'choose-folder'},
    permission:{label:'03 / ACCESS LOST',icon:'lock',title:'폴더를 다시<br>연결해 주세요.',copy:'폴더에 접근할 수 없어요. 읽던 위치와 책갈피는 보관하고 있습니다.',button:'폴더 다시 연결',action:'choose-folder'},
    missing:{label:'04 / FILE MISSING',icon:'broken',title:'읽던 파일을<br>찾을 수 없어요.',copy:'파일이 옮겨지거나 삭제되었을 수 있어요. 폴더에서 확인해 주세요.',button:'폴더 보기',action:'folder'}
  };
  const states=new Map();
  document.querySelector('#boards').innerHTML=configs.map(([view,title,en,note],i)=>`<article class="screen-card" data-screen="${view}"><div class="screen-caption"><span>0${i+1}</span><h2>${title}</h2><small>${en}</small></div><div class="phone" data-phone="${i}" aria-label="${title} 시안"><div class="statusbar"><b>9:41</b><div>${icon('signal')}${icon('wifi')}${icon('battery')}</div></div><div class="phone-content"></div><div class="systembar"><span></span></div></div><p class="screen-note">${note}</p></article>`).join('');
  document.querySelector('#edge-states').innerHTML=Object.entries(edgeConfigs).map(([id,s])=>`<article class="edge-card"><span class="edge-label">${s.label}</span>${icon(s.icon)}<h3>${s.title}</h3><p>${s.copy}</p><button class="secondary" data-scenario="${id}">${s.button}</button></article>`).join('');
  const nav=current=>`<nav class="navigation" aria-label="앱 메뉴">${[['folder','folder','폴더'],['recent','clock','최근 읽기'],['settings','settings','설정']].map(([view,i,label])=>btn(icon(i)+`<span>${label}</span>`,view,'',current===view?'aria-current="page"':'')).join('')}</nav>`;
  const top=(title,back='folder',right='')=>`<header class="topbar">${ib('back','뒤로',back)}<h2>${title}</h2>${right}</header>`;
  const label=(title,detail='')=>`<div class="section-label"><strong>${title}</strong><small>${detail}</small></div>`;
  const docIcon=()=>`<span class="document-icon">${icon('file')}<small>TXT</small></span>`;
  const bookAttrs=id=>`data-book="${id}"`;
  const bookButton=source=>{const b=hiddenRecords.has(source.id)?{...source,progress:0}:source;return btn(`${docIcon()}<span class="file-copy"><strong>${esc(b.title)}.txt</strong><small class="${b.progress?'read-meta':''}">${b.progress?`${b.progress}% · ${b.when} 읽음`:'읽기 전'}</small></span>`,'reader','file-open',bookAttrs(b.id));};
  const continueCard=()=>`<button class="continue-card" data-action="reader" data-book="0"><span class="continue-kicker">${icon('book')} 다시 펼치는 이야기</span><strong class="continue-title">비가 그친 오후</strong><span class="continue-line">창가에 남은 빛, 그다음 문장.</span><span class="continue-bottom"><span><b>42.3%</b><small>읽는 중</small></span>${icon('arrow')}</span><span class="progress-track"><i style="width:42.3%"></i></span></button>`;
  function folder(s){return `<section class="page"><header class="app-header"><div><span class="kicker">나의 읽는 자리</span><h2>${brand('app-brand-mark')}여백<span>이야기가 머무는 곳</span></h2></div>${ib('settings','앱 설정','settings')}</header><div class="scroll padded">${hiddenRecords.has(0)?'':continueCard()}<div class="path">${btn(icon(s.child?'back':'folder')+`<span>${esc(prefs.folder)}${s.child?' / '+esc(s.child):''}</span>`,'root-folder')}${ib('refresh','폴더 새로고침','refresh')}</div>${label(s.child||'폴더와 파일','이름순')}${s.child?'':`<div class="folder-row">${['완결','메모'].map((n,i)=>btn(icon('folder')+`<span>${n}</span><small>${i?2:8}</small>`,'child-folder','folder-tile',`data-folder="${n}"`)).join('')}</div>`}${books.map(b=>`<div class="file-row">${bookButton(b)}${ib('more',b.title+' 파일 정보','info',bookAttrs(b.id))}</div>`).join('')}</div>${nav('folder')}</section>`;}
  function recent(){const items=books.filter(b=>b.progress&&!hiddenRecords.has(b.id));return `<section class="page">${top('최근 읽기')}<div class="scroll padded"><p class="recent-lead">접어 둔 문장으로,<br>다시 돌아오는 시간.</p><div class="recent-count">${icon('clock')} <span>${items.length}권의 읽기 기록</span></div>${items.map(b=>`<div class="recent-item"><div>${bookButton(b)}${ib('more',b.title+' 기록 관리','record-menu',bookAttrs(b.id))}</div><span class="progress-track"><i style="width:${b.progress}%"></i></span><p class="source">${esc(prefs.folder)}</p></div>`).join('')||'<p class="empty-inline">아직 읽기 기록이 없어요.<br>폴더에서 첫 책을 펼쳐 보세요.</p>'}<p class="helper" style="margin-top:23px">파일을 옮기지 않아도<br>읽던 자리는 여기 남아 있어요.</p></div>${nav('recent')}</section>`;}
  function settings(){return `<section class="page">${top('설정')}<div class="scroll padded"><div class="section-intro"><span class="kicker">나에게 맞는 여백</span><h3>읽는 자리를<br>정해 보세요.</h3></div>${label('앱 설정','앱 전체에 적용')}<button class="start-folder" data-action="choose-folder">${icon('folder')}<span><strong>시작 폴더</strong><span class="folder-value">${esc(prefs.folder)}</span></span>${icon('arrow')}</button><p class="helper">앱을 새로 열 때 이 폴더에서 시작합니다.</p><div class="setting-label"><strong>앱 테마</strong><small>폴더 · 메뉴 · 설정</small></div><div class="segments">${[['system','기기 설정'],['light','라이트'],['dark','다크']].map(([v,l])=>btn(l,'app-theme','',`data-value="${v}" aria-pressed="${prefs.appTheme===v}"`)).join('')}</div>${label('뷰어 설정','파일 형식별로 적용')}<button class="settings-link" data-action="options" data-from="settings"><span class="type-icon">가</span><span><strong>TXT 뷰어</strong><small>글꼴 · 크기 · 간격 · 본문 색상</small></span>${icon('arrow')}</button><div class="resume-note"><strong>읽던 책, 다음에도 이어서</strong><p>책을 읽다가 앱을 닫으면<br>다음 실행에 이어 읽기를 묻습니다.</p></div><div class="quiet-signature">${brand('signature-mark')}여백<span>읽는 시간, 나만의 자리.</span></div></div>${nav('settings')}</section>`;}
  function reader(s){const b=books[s.book];const isMarked=(marks.get(s.book)||[]).some(m=>m.index===s.currentIndex);return `<section class="page reader-page ${s.chrome?'':'chrome-hidden'}"><header class="reader-header">${ib('back','폴더로 돌아가기','folder')}<div><small>지금 읽는 책 · TXT</small><strong>${esc(b.title)}</strong></div><span class="reader-percent" aria-label="읽기 진행률"><span class="progress-value">${s.progress.toFixed(1)}%</span></span>${ib('bookmark',isMarked?'현재 문장의 책갈피 해제':'현재 문장에 책갈피 저장','bookmark',`aria-pressed="${isMarked}"`)}</header><div class="reading-scroll ${prefs.font}" tabindex="0" aria-label="TXT 본문. 세로로 읽기. 탭 또는 Enter로 독서 메뉴 ${s.chrome?'숨기기':'표시'}" style="--text-size:${prefs.size}px;--text-line:${prefs.line};--text-margin:${prefs.margin}px"><article><p class="chapter-label">첫 번째 이야기</p><h2>창가에 남은 빛</h2>${paragraphs.map((p,i)=>`<p data-paragraph="${i}">${esc(p)}</p>`).join('')}</article></div><footer class="reader-controls"><div class="reader-actions">${btn(icon('search')+'<span>검색</span>','search')}${btn(icon('bookmark')+'<span>책갈피</span>','bookmarks')}${btn('<span class="type-icon">가</span><span>읽기 설정</span>','options')}${btn(icon('more')+'<span>책 정보</span>','info')}${btn(icon('move')+'<span>이동</span>','move')}</div></footer></section>`;}
  const sheet=(title,sub,content,close='close-overlay',extra='')=>`<div class="shade"></div><section class="sheet overlay ${extra}" role="dialog" aria-modal="true" aria-label="${title}"><span class="sheet-grip"></span><header class="sheet-header"><div><span>${sub}</span><h2>${title}</h2></div>${ib('close',title+' 닫기',close)}</header><div class="sheet-body scroll">${content}</div></section>`;
  function options(){return sheet('읽기 설정','TXT 전용',`<div class="text-preview ${prefs.font}" style="font-size:${prefs.size}px;line-height:${prefs.line}">어떤 이야기는<br>잠시 머물러도 좋았다.</div><div class="setting-label"><strong>글꼴</strong></div><div class="segments font-choices">${[['sans','고딕'],['serif','명조']].map(([v,l])=>btn(l,'font','',`data-value="${v}" aria-pressed="${prefs.font===v}"`)).join('')}</div><div class="size-row"><span>글자 크기</span><div class="stepper">${btn('−','size-down','',`aria-label="글자 크기 줄이기" ${prefs.size<=14?'disabled':''}`)}<output class="size-value">${prefs.size}</output>${btn('+','size-up','',`aria-label="글자 크기 늘리기" ${prefs.size>=32?'disabled':''}`)}</div></div>${[['line','줄간격',1.3,2.1,.05],['margin','좌우 여백',16,40,1]].map(([n,l,min,max,step])=>`<div class="range-row"><label>${l}<output data-output="${n}">${n==='line'?prefs[n].toFixed(2):prefs[n]}</output></label><input type="range" min="${min}" max="${max}" step="${step}" value="${prefs[n]}" data-range="${n}" aria-label="${l}"></div>`).join('')}<div class="setting-label"><strong>본문 색상</strong><small>TXT에만 적용</small></div><div class="paper-choices">${[['follow','앱 테마'],['light','라이트'],['sepia','세피아'],['dark','다크']].map(([v,l])=>btn(`<span class="paper-chip" data-paper="${v}">가${prefs.paper===v?icon('check'):''}</span><span>${l}</span>`,'paper','paper-choice',`data-value="${v}" aria-pressed="${prefs.paper===v}"`)).join('')}</div>${btn('TXT 설정 기본값 복원','reset-txt','text-button reset-txt')}<p class="scope-note">시작 폴더와 독서 기록은 유지됩니다.</p>`);}
  const dialog=(content,close=true)=>`<div class="shade"></div><section class="dialog overlay" role="dialog" aria-modal="true" aria-label="${close?'책과 폴더 설정':'이어서 읽기 확인'}">${close?'<div class="dialog-close">'+ib('close','대화상자 닫기','close-overlay')+'</div>':''}${content}</section>`;
  const resume=()=>dialog(`<div class="dialog-symbol">${icon('book')}</div><span class="kicker">읽던 이야기가 있어요</span><h2>이어서<br>읽으시겠습니까?</h2><div class="resume-book">${icon('file')}<div><strong>비가 그친 오후.txt</strong><small>마지막 읽은 위치 · 42.3%</small></div></div><p>마지막으로 읽던 문장부터<br>이야기를 이어갈 수 있어요.</p><div class="dialog-actions">${btn('폴더 보기','dismiss-resume','secondary')}${btn('이어서 읽기 '+icon('arrow'),'reader','primary','data-book="0"')}</div>`,false);
  const searchResults=query=>{if(!query.trim())return '<p class="empty-inline">찾고 싶은 단어나 문장을 입력해 주세요.</p>';const found=paragraphs.map((text,index)=>({text,index})).filter(x=>x.text.includes(query));return `<p class="search-summary">${found.length}개의 결과 · 본문에서 찾기</p>${found.map(x=>btn(`<small>${x.index+1}번째 문단</small>${esc(x.text).split(esc(query)).join('<mark>'+esc(query)+'</mark>')}`,'jump','result',`data-index="${x.index}"`)).join('')||'<p class="empty-inline">일치하는 문장이 없어요.<br>다른 검색어로 찾아보세요.</p>'}`;};
  function overlay(s){const b=books[s.infoBook??s.book];
    if(s.overlay==='move')return sheet('읽기 위치 이동','TXT · '+esc(books[s.book].title),`<div class="move-destination"><span>이동할 위치</span><output class="move-value">${s.moveProgress.toFixed(1)}<small>%</small></output></div><input class="move-range" type="range" min="0" max="100" step="0.1" value="${s.moveProgress}" data-range="destination" aria-label="이동할 읽기 위치"><div class="move-endpoints"><span>처음 · 0%</span><span>끝 · 100%</span></div><div class="dialog-actions">${btn('취소','close-overlay','secondary')}${btn('이 위치로 이동','apply-move','primary')}</div>`,'close-overlay','move-sheet');
    if(s.overlay==='options')return options();
    if(s.overlay==='resume')return resume();
    if(s.overlay==='folder')return dialog(`<span class="kicker">시작 폴더 변경</span><h2>어디에서<br>읽을까요?</h2><p>앱을 새로 열 때<br>선택한 폴더에서 시작합니다.</p>${['Books / 소설','Books / 에세이','Documents / 읽을거리'].map(f=>btn(icon('folder')+`<span>${f}</span>`+icon(prefs.folder===f?'check':'arrow'),'select-folder','folder-choice',`data-value="${f}"`)).join('')}`);
    if(s.overlay==='info')return dialog(`<span class="kicker">책 정보</span><h2>${esc(b.title)}</h2><dl class="file-info"><dt>위치</dt><dd>${esc(prefs.folder)}</dd><dt>형식</dt><dd>TXT · ${b.size}</dd><dt>인코딩</dt><dd>${encodings.get(b.id)||'UTF-8'}</dd></dl>${btn('글자가 깨져 보이나요?','encoding','secondary wide')}<p>인코딩은 이 책에만 적용됩니다.</p>`);
    if(s.overlay==='encoding')return sheet('인코딩','이 책에만 적용',`<p class="helper">글자가 올바르게 보이는 형식을 선택해 주세요.</p><select class="encoding-select" aria-label="이 책의 인코딩">${['UTF-8','UTF-16 LE','UTF-16 BE','CP949','EUC-KR'].map(x=>`<option ${(encodings.get(b.id)||'UTF-8')===x?'selected':''}>${x}</option>`).join('')}</select><div class="encoding-preview">비가 그친 뒤의 골목은<br>평소보다 조금 넓어 보였다.</div><p class="helper">적용 후 읽던 위치를 다시 확인해 주세요.</p><div class="dialog-actions">${btn('돌아가기','info','secondary')}${btn('이 책에 적용','apply-encoding','primary')}</div>`);
    if(s.overlay==='search')return sheet('본문 검색','TXT · '+esc(books[s.book].title),`<label class="search-field">${icon('search')}<input type="search" aria-label="본문 검색어" placeholder="단어나 문장을 입력하세요" value="${esc(s.query)}"></label><div class="search-results">${searchResults(s.query)}</div><button class="text-button wide" data-action="close-overlay">검색 전 읽던 위치로 돌아가기</button>`);
    if(s.overlay==='bookmarks')return sheet('책갈피','이 책의 읽는 자리',(marks.get(s.book)||[]).map(m=>`<div class="bookmark-row">${btn(`<small>${m.index+1}번째 문단</small>${esc(m.text)}`,'jump','result',`data-index="${m.index}"`)}${ib('trash','책갈피 삭제','delete-bookmark',`data-index="${m.index}"`)}</div>`).join('')||'<p class="empty-inline">아직 접어 둔 문장이 없어요.<br>독서 화면 위의 책갈피를 눌러 보세요.</p>');
    if(s.overlay==='record-menu')return dialog(`<span class="kicker">읽기 기록</span><h2>${esc(b.title)}</h2><p>마지막 읽은 위치 · ${b.progress}%</p><div class="dialog-actions">${btn('이어서 읽기','reader','primary',bookAttrs(b.id))}</div>${btn('이 책의 읽기 기록 삭제','confirm-delete','text-button danger wide')}`);
    if(s.overlay==='confirm-delete')return dialog(`<span class="kicker">읽기 기록 삭제</span><h2>이 책의 기록을<br>삭제할까요?</h2><p>읽던 위치와 책갈피가 삭제됩니다.<br>원본 TXT 파일은 그대로 유지됩니다.</p><div class="dialog-actions">${btn('취소','close-overlay','secondary')}${btn('기록 삭제','delete-record','secondary danger')}</div>`);
    return '';
  }
  function edgePage(id){const e=edgeConfigs[id];return `<section class="page">${top(id==='onboard'?'여백':'폴더 안내')}<div class="scroll padded" style="display:flex;flex-direction:column;justify-content:center"><div class="dialog-symbol">${icon(e.icon)}</div><div class="section-intro"><h3>${e.title}</h3></div><p class="helper">${e.copy}</p><div class="dialog-actions">${btn(e.button,e.action,'primary')}</div>${id==='permission'?btn('다시 시도','retry','text-button wide'):''}</div>${nav('folder')}</section>`;}
  function setPalette(){document.body.dataset.mode=mode();document.querySelectorAll('[data-review-mode]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.reviewMode===mode())));document.querySelector('#download').href=`previews/${mode()}.png`;}
  function capture(phone,s){const sc=phone.querySelector('.reading-scroll');if(sc){s.scroll=sc.scrollTop;const top=sc.getBoundingClientRect().top+64;const p=[...sc.querySelectorAll('[data-paragraph]')].find(p=>p.getBoundingClientRect().bottom>top);if(p){s.currentIndex=Number(p.dataset.paragraph);s.anchor={index:s.currentIndex,fraction:(top-p.getBoundingClientRect().top)/Math.max(1,p.offsetHeight)};}}}
  function render(phone,focus=false){const s=states.get(phone);phone.dataset.paper=prefs.paper;phone.dataset.reading=String(s.view==='reader'&&!s.overlay);phone.style.setProperty('--reader-accent',prefs.paper==='follow'?'var(--accent)':prefs.paper==='dark'?'#b4c4a1':prefs.paper==='sepia'?'#645535':'#536348');const base=s.view==='reader'?reader(s):s.view==='settings'?settings():s.view==='recent'?recent():edgeConfigs[s.view]?edgePage(s.view):folder(s);phone.querySelector('.phone-content').innerHTML=`<div class="base-page" ${s.overlay?'inert':''}>${base}</div>${overlay(s)}`;const sc=phone.querySelector('.reading-scroll');if(sc){if(s.anchor){const p=sc.querySelector(`[data-paragraph="${s.anchor.index}"]`);if(p)sc.scrollTop=p.offsetTop+s.anchor.fraction*p.offsetHeight-64;}else if(s.scroll!==null)sc.scrollTop=s.scroll;else sc.scrollTop=(sc.scrollHeight-sc.clientHeight)*s.progress/100;sc.addEventListener('scroll',()=>{capture(phone,s);s.progress=100*sc.scrollTop/Math.max(1,sc.scrollHeight-sc.clientHeight);phone.querySelector('.progress-value').textContent=s.progress.toFixed(1)+'%';const b=phone.querySelector('[data-action="bookmark"]');b?.setAttribute('aria-pressed',String((marks.get(s.book)||[]).some(m=>m.index===s.currentIndex)));},{passive:true});}if(focus)phone.querySelector('.overlay button,.overlay input,.page button')?.focus({preventScroll:true});}
  function renderAll(){states.forEach(captureState);setPalette();states.forEach((s,p)=>render(p));}
  function captureState(s,p){capture(p,s);}
  function resetStates(){states.clear();document.querySelectorAll('.phone').forEach((p,i)=>{const v=configs[i][0];states.set(p,{initial:v,view:['resume','options'].includes(v)?(v==='resume'?'folder':'reader'):v,overlay:v==='resume'?'resume':v==='options'?'options':null,book:0,infoBook:null,child:null,chrome:true,scroll:null,anchor:null,currentIndex:4,progress:42.3,query:'문장',optionsFrom:null,moveProgress:42.3});});setPalette();states.forEach((s,p)=>render(p));}
  function toast(phone,message){phone.querySelector('.toast')?.remove();const t=document.createElement('div');t.className='toast';t.setAttribute('role','status');t.textContent=message;phone.querySelector('.phone-content').append(t);setTimeout(()=>t.remove(),2800);}
  function toggleReaderChrome(phone){
    const s=states.get(phone);
    if(s.view!=='reader'||s.overlay)return;
    s.chrome=!s.chrome;
    phone.querySelector('.reader-page').classList.toggle('chrome-hidden',!s.chrome);
    const sc=phone.querySelector('.reading-scroll');
    sc.setAttribute('aria-label',`TXT 본문. 세로로 읽기. 탭 또는 Enter로 독서 메뉴 ${s.chrome?'숨기기':'표시'}`);
    sc.focus({preventScroll:true});
  }
  // Treat a stationary short tap as a menu toggle. Native scrolling and text selection win.
  let readingGesture=null;
  document.addEventListener('pointerdown',event=>{
    const sc=event.target.closest('.reading-scroll');
    readingGesture=null;
    if(!sc||!event.isPrimary||event.button!==0)return;
    const phone=sc.closest('.phone');
    if(states.get(phone).overlay)return;
    readingGesture={phone,sc,id:event.pointerId,x:event.clientX,y:event.clientY,top:sc.scrollTop,time:performance.now(),moved:false};
  },{passive:true});
  document.addEventListener('pointermove',event=>{
    if(readingGesture?.id===event.pointerId&&Math.hypot(event.clientX-readingGesture.x,event.clientY-readingGesture.y)>8)readingGesture.moved=true;
  },{passive:true});
  document.addEventListener('pointercancel',()=>{readingGesture=null;},{passive:true});
  document.addEventListener('click',event=>{
    const sc=event.target.closest('.reading-scroll');
    if(!sc)return;
    const gesture=readingGesture;
    readingGesture=null;
    if(event.detail>1||!window.getSelection()?.isCollapsed)return;
    const phone=sc.closest('.phone');
    if(event.detail!==0&&(!gesture||gesture.phone!==phone||gesture.moved||performance.now()-gesture.time>350||Math.abs(sc.scrollTop-gesture.top)>2))return;
    toggleReaderChrome(phone);
  });
  resetStates();
  media.addEventListener('change',()=>{if(prefs.appTheme==='system')renderAll();});
  document.querySelectorAll('[data-review-mode]').forEach(b=>b.addEventListener('click',()=>{prefs.appTheme=b.dataset.reviewMode;save();renderAll();}));
  document.querySelector('#reset-demo').addEventListener('click',()=>{prefs={...defaults,appTheme:mode()};hiddenRecords.clear();marks.clear();marks.set(0,[{index:4,text:sample[4]}]);marks.set(1,[]);marks.set(2,[]);encodings.clear();save();resetStates();});
  document.querySelectorAll('[data-scenario]').forEach(b=>b.addEventListener('click',()=>{const phone=document.querySelector('[data-phone="0"]');const s=states.get(phone);s.view=b.dataset.scenario;s.overlay=null;render(phone,true);phone.scrollIntoView({block:'center',behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});}));
  document.addEventListener('click',event=>{const button=event.target.closest('[data-action]');if(!button)return;const phone=button.closest('.phone');if(!phone)return;const s=states.get(phone);const action=button.dataset.action;capture(phone,s);
    if(['folder','recent','settings','dismiss-resume'].includes(action)){s.view=action==='dismiss-resume'?'folder':action;s.overlay=null;render(phone,true);return;}
    if(action==='reader'){const id=Number(button.dataset.book??s.book);if(id!==s.book){s.scroll=null;s.anchor=null;s.progress=books[id].progress;}s.book=id;s.view='reader';s.overlay=null;s.chrome=true;hiddenRecords.delete(id);render(phone,true);return;}
    if(action==='root-folder'){s.child=null;render(phone);return;}
    if(action==='child-folder'){s.child=button.dataset.folder;render(phone);return;}
    if(action==='refresh'){toast(phone,'폴더가 최신 상태입니다.');return;}
    if(action==='retry'){toast(phone,'접근 권한을 확인해 주세요. 폴더를 다시 연결할 수 있습니다.');return;}
    if(action==='choose-folder'){s.overlay='folder';render(phone,true);return;}
    if(action==='select-folder'){prefs.folder=button.dataset.value;states.forEach(x=>x.child=null);s.overlay=null;if(edgeConfigs[s.view])s.view='folder';save();renderAll();toast(phone,'시작 폴더를 변경했어요.');return;}
    if(action==='info'||action==='record-menu'){s.infoBook=Number(button.dataset.book??(s.overlay==='encoding'?s.infoBook:s.book));s.overlay=action;render(phone,true);return;}
    if(action==='move'){s.moveProgress=s.progress;s.overlay='move';render(phone,true);return;}
    if(action==='apply-move'){s.progress=s.moveProgress;s.anchor=null;s.scroll=null;s.overlay=null;render(phone,true);return;}
    if(action==='options'){s.optionsFrom=s.view;s.overlay='options';render(phone,true);return;}
    if(['search','bookmarks','encoding','confirm-delete'].includes(action)){s.overlay=action;render(phone,true);return;}
    if(action==='close-overlay'){s.overlay=null;render(phone,true);return;}
    if(action==='apply-encoding'){const value=phone.querySelector('.encoding-select').value;encodings.set(s.infoBook??s.book,value);s.overlay='info';render(phone,true);toast(phone,'이 책의 인코딩을 '+value+'로 설정했어요.');return;}
    if(action==='delete-record'){const id=s.infoBook??s.book;hiddenRecords.add(id);marks.set(id,[]);s.overlay=null;renderAll();toast(phone,'읽기 기록을 삭제했어요. 원본 파일은 유지됩니다.');return;}
    if(action==='bookmark'){const saved=marks.get(s.book)||[];const index=s.currentIndex;const exists=saved.some(x=>x.index===index);marks.set(s.book,exists?saved.filter(x=>x.index!==index):[...saved,{index,text:paragraphs[index]}]);render(phone);toast(phone,exists?'책갈피를 해제했어요.':'이 문장에 책갈피를 저장했어요.');return;}
    if(action==='delete-bookmark'){marks.set(s.book,(marks.get(s.book)||[]).filter(x=>x.index!==Number(button.dataset.index)));render(phone,true);return;}
    if(action==='jump'){s.anchor={index:Number(button.dataset.index),fraction:0};s.overlay=null;s.view='reader';render(phone,true);return;}
    if(action==='app-theme')prefs.appTheme=button.dataset.value;
    if(action==='font')prefs.font=button.dataset.value;
    if(action==='paper')prefs.paper=button.dataset.value;
    if(action==='size-up')prefs.size=Math.min(32,prefs.size+1);
    if(action==='size-down')prefs.size=Math.max(14,prefs.size-1);
    if(action==='reset-txt'){const {appTheme,folder}=prefs;prefs={...defaults,appTheme,folder};}
    save();renderAll();
    // Keep keyboard focus on the setting just used, across template updates.
    const value=button.dataset.value;phone.querySelector(`[data-action="${action}"]${value?`[data-value="${value}"]`:''}`)?.focus({preventScroll:true});
  });
  document.addEventListener('input',event=>{const phone=event.target.closest('.phone');if(!phone)return;const s=states.get(phone);if(event.target.type==='search'){s.query=event.target.value;phone.querySelector('.search-results').innerHTML=searchResults(s.query);return;}const name=event.target.dataset.range;if(!name)return;const value=Number(event.target.value);if(name==='destination'){s.moveProgress=value;phone.querySelector('.move-value').innerHTML=value.toFixed(1)+'<small>%</small>';return;}states.forEach(captureState);prefs[name]=value;save();states.forEach((state,p)=>{p.querySelectorAll('.reading-scroll').forEach(sc=>{sc.style.setProperty('--text-line',prefs.line);sc.style.setProperty('--text-margin',prefs.margin+'px');if(state.anchor){const para=sc.querySelector(`[data-paragraph="${state.anchor.index}"]`);if(para)sc.scrollTop=para.offsetTop+state.anchor.fraction*para.offsetHeight-64;}});const output=p.querySelector(`[data-output="${name}"]`);if(output)output.textContent=name==='line'?value.toFixed(2):value;const input=p.querySelector(`[data-range="${name}"]`);if(input)input.value=value;const preview=p.querySelector('.text-preview');if(preview)preview.style.lineHeight=prefs.line;});});
  document.addEventListener('keydown',event=>{const phone=event.target.closest('.phone');if(!phone)return;const s=states.get(phone);if(event.key==='Enter'&&event.target.matches('.reading-scroll')&&!event.repeat&&!s.overlay){event.preventDefault();toggleReaderChrome(phone);return;}if(event.key==='Escape'&&s.overlay){event.preventDefault();s.overlay=null;render(phone,true);return;}const overlay=phone.querySelector('.overlay');if(event.key==='Tab'&&overlay){const nodes=[...overlay.querySelectorAll('button:not(:disabled),input,select')];const first=nodes[0],last=nodes.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}});
})();
