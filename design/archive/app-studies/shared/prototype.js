/* Offline design prototypes. Original sample text; no external requests. */
(() => {
  'use strict';
  const themes = {
    paper: { number: '01', name: '종이의 여백', en: 'PAPER & PAUSE', angle: '문장과 여백', line: '읽는 순간,\n조용해지는 화면.', description: '따뜻한 종이색과 명조 제목. 메뉴보다 문장이 먼저 보이는, 작은 문고본 같은 독서 경험.', tags: ['문장 중심', '넉넉한 여백', '종이색'], font: 'serif', color: 'sepia', size: 18, card: '다시 펼치는 이야기', folderLabel: '나의 읽는 자리', tradeoff: '분위기와 몰입을 우선합니다. 한 화면에 보이는 파일 수는 적습니다.' },
    flow: { number: '02', name: '가볍게, 다음 문장', en: 'EVERYDAY FLOW', angle: '한 손으로 쓰는 일상', line: '한 손으로 열고,\n자연스럽게 이어 읽기.', description: '또렷한 색과 부드러운 형태. 자주 쓰는 동작을 아래로 모아 안드로이드 앱처럼 익숙하게.', tags: ['한 손 조작', '명확한 동작', '하단 메뉴'], font: 'sans', color: 'light', size: 18, card: '지금, 이어 읽기', folderLabel: '오늘도 한 편', tradeoff: '조작을 쉽게 발견할 수 있습니다. 독서 외 화면의 시각적 존재감은 큽니다.' },
    night: { number: '03', name: '밤의 문장', en: 'AFTER HOURS', angle: '야간의 긴 독서', line: '하루의 끝에,\n한 문장 더.', description: '짙은 먹색 위에 낮게 켠 호박빛. 검은 화면 안에서도 조작과 본문을 또렷하게 구분합니다.', tags: ['어두운 기본 화면', '따뜻한 본문', '몰입'], font: 'serif', color: 'dark', size: 18, card: '어젯밤의 문장', folderLabel: '고요한 독서 시간', tradeoff: '밤의 분위기에 초점을 맞췄습니다. 실제 저조도 가독성과 눈의 편안함은 기기에서 확인해야 합니다.' },
    archive: { number: '04', name: '정돈된 서재', en: 'A PLACE FOR EVERY FILE', angle: '폴더와 파일의 명료함', line: '파일은 제자리에.\n이야기는 읽던 자리에.', description: '경로와 목록을 앞에 두는 파일 탐색형 화면. 폴더 구조를 유지하면서 읽기 기록을 얹었습니다.', tags: ['폴더 우선', '정보 밀도', '경로 탐색'], font: 'sans', color: 'light', size: 18, card: '최근 열람', folderLabel: '연결된 폴더', tradeoff: '여러 파일을 빠르게 구분하기 좋습니다. 감성적인 표지나 장식은 적습니다.' },
    focus: { number: '05', name: '선명한 읽기', en: 'NOTHING BETWEEN', angle: '큰 글씨와 높은 대비', line: '더 크게, 더 분명하게.\n읽기에 필요한 것만.', description: '검정과 흰색, 큰 글씨, 넓은 버튼. 그림자와 장식을 덜어 조작의 의미가 바로 보이도록.', tags: ['고대비', '큰 글씨', '명시적인 버튼'], font: 'sans', color: 'light', size: 20, card: '읽던 책', folderLabel: '시작 폴더', tradeoff: '선명한 구분과 큰 조작 영역을 우선합니다. 실제 전자잉크 잔상·갱신 성능을 검증한 시안은 아닙니다.' }
  };
  const key = document.body.dataset.theme;
  const t = themes[key];
  if (!t) return;
  const paths = {
    folder: '<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M3 9h18"/>',
    file: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
    arrow: '<path d="m9 5 7 7-7 7"/>', back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 6a8 8 0 0 1 13 2M18 18a8 8 0 0 1-13-2"/>',
    book: '<path d="M12 5c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1Z"/><path d="M12 5v15"/>',
    bookmark: '<path d="M6 3h12v18l-6-4-6 4Z"/>',
    sliders: '<path d="M4 6h16M4 12h16M4 18h16"/><path d="M8 3v6M16 9v6M10 15v6"/>',
    settings: '<path d="m9 3-1 3-3 1-2 4 2 3 1 4 4 2 3-1 4-1 2-4-1-3-1-4-4-2Z"/><circle cx="11.5" cy="11.5" r="3"/>',
    more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    moon: '<path d="M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11Z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    signal: '<path d="M4 18v-3M9 18v-6M14 18V9M19 18V5"/>',
    wifi: '<path d="M3 9a14 14 0 0 1 18 0M6 12a9 9 0 0 1 12 0M9 15a4 4 0 0 1 6 0"/><circle cx="12" cy="18" r=".8"/>',
    battery: '<rect x="3" y="7" width="17" height="10" rx="2"/><path d="M22 10v4M6 10h10v4H6Z"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1 1M18 18l1 1M5 19l1-1M18 6l1-1"/>'
  };
  const icon = (name, extra = '') => `<svg class="icon ${extra}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.file}</svg>`;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
  const storageKey = `yeobaek-design-${key}-v1`;
  const defaults = { size: t.size, line: 1.85, margin: 25, font: t.font, color: t.color, folder: 'Books / 소설', appColor: 'design' };
  let prefs = {...defaults};
  try { prefs = {...defaults, ...JSON.parse(localStorage.getItem(storageKey) || '{}')}; } catch (_) { /* Storage is optional for local files. */ }
  const save = () => { try { localStorage.setItem(storageKey, JSON.stringify(prefs)); } catch (_) {} };
  const sample = [
    '비가 그친 뒤의 골목은 평소보다 조금 넓어 보였다. 가게 앞에 놓인 작은 화분들에는 아직 물방울이 남아 있었다. 나는 서두르지 않고 그 사이를 걸었다.',
    '익숙한 길에도 처음 보는 장면이 있었다. 닫힌 창문에 비친 하늘, 누군가 반듯하게 접어 둔 우산, 빵집 문틈으로 흘러나오는 따뜻한 냄새 같은 것들.',
    '“오늘은 조금 늦어도 괜찮겠네.”',
    '누구에게 하는 말인지도 모른 채 작게 중얼거렸다. 주머니 속 휴대전화는 조용했고, 다음 모퉁이까지는 아직 걸어갈 시간이 남아 있었다.',
    '집으로 돌아오자 창가에 오후의 빛이 길게 놓여 있었다. 읽다 만 책을 펼쳤다. 마지막으로 접어 둔 문장 옆에 손끝을 가만히 올려놓았다.',
    '그 문장은 며칠 전과 똑같았지만, 읽는 마음은 조금 달랐다. 어떤 이야기는 끝을 향해 서둘러 가지 않아도 좋았다. 그저 잠시 머물 수 있다면 충분했다.',
    '창밖에서 다시 바람이 불었다. 젖은 잎이 서로 닿는 소리를 들으며, 나는 다음 문장으로 천천히 시선을 옮겼다.'
  ];
  const action = (label, act, className = '', attrs = '') => `<button type="button" class="${className}" data-action="${act}" ${attrs}>${label}</button>`;
  const ib = (name, label, act, attrs = '') => action(icon(name), act, 'icon-button', `aria-label="${label}" title="${label}" ${attrs}`);
  const pill = (label, act, selected, attrs = '') => action(label, act, `choice ${selected ? 'selected' : ''}`, `aria-pressed="${selected}" ${attrs}`);
  const states = new Map();
  const titles = ['폴더 서재', '다음 실행 · 이어 읽기', 'TXT 독서', '앱 설정', 'TXT 전용 설정'];
  const views = ['folder', 'resume', 'reader', 'settings', 'options'];
  document.title = `${t.number}. ${t.name} — 여백 디자인`;
  const cardNames = ['01-paper', '02-flow', '03-night', '04-archive', '05-focus'];
  document.querySelector('#app').innerHTML = `
    <header class="review-header">
      <a href="../index.html" class="back-to-gallery">${icon('back')} 전체 시안</a>
      <div class="review-brand">YEOBAEK <span>DESIGN STUDY / ${t.number}</span></div>
      <a class="download-link" href="../previews/${cardNames[Number(t.number)-1]}.png" download>PNG 저장 ${icon('arrow')}</a>
    </header>
    <main>
      <section class="concept-intro"><div><p class="eyebrow">${t.number} / ${t.en}</p><h1>${t.line.replace('\n', '<br>')}</h1></div><div class="concept-description"><h2>${t.name}</h2><p>${t.description}</p><div class="tag-list">${t.tags.map(x=>`<span>${x}</span>`).join('')}</div></div></section>
      <div class="preview-instructions"><span><i></i> 화면 안의 버튼을 눌러 흐름을 살펴보세요.</span>${action('시안 기본값으로', 'reset-all', 'reset-link')}</div>
      <section class="screens" aria-label="다섯 가지 앱 화면">${views.map((view,i)=>`<article class="screen-card"><div class="screen-caption"><span>0${i+1}</span><h3>${titles[i]}</h3></div><div class="phone" data-phone="${i}" aria-label="${titles[i]} 시안"><div class="status-bar"><b>9:41</b><div>${icon('signal')}${icon('wifi')}${icon('battery')}</div></div><div class="phone-app"></div><div class="system-nav"><span></span></div></div></article>`).join('')}</section>
      <section class="design-notes"><div><span>DESIGN INTENT</span><h2>${t.angle}</h2><p>${t.tradeoff}</p></div><div><span>ONE PRODUCT, FIVE VIEWS</span><p>시작 폴더 → 파일 열기 → 연속 스크롤 → 다음 실행의 이어 읽기.<br>앱 설정과 TXT 옵션은 독립적으로 동작합니다.</p></div><div><span>PROTOTYPE SCOPE</span><p>Android 앱의 디자인 시안입니다. 폴더 선택은 샘플로 시연하며 실제 기기 파일에 접근하지 않습니다. 본문은 직접 작성한 예시입니다.</p></div></section>
      <nav class="concept-nav" aria-label="다른 시안">${Object.entries(themes).map(([k,v],i)=>`<a ${k===key?'aria-current="page"':''} href="../../../../../${cardNames[i]}/index.html">${v.number}<span>${v.name}</span></a>`).join('')}</nav>
    </main>`;

  const nav = current => `<nav class="app-nav" aria-label="앱 메뉴">${[['folder','folder','폴더'],['recent','clock','최근 읽기'],['settings','sliders','설정']].map(([view,ico,label])=>action(`${icon(ico)}<span>${label}</span>`,view,current===view?'active':'')).join('')}</nav>`;
  const top = (title, right = '', back = 'folder', cls = '') => `<header class="app-top ${cls}">${ib('back','뒤로',back)}<h2>${title}</h2>${right || '<span class="top-spacer"></span>'}</header>`;
  const dividerTitle = (label, right = '') => `<div class="section-title"><h3>${label}</h3>${right}</div>`;
  function recentCard() {
    return `<button class="continue-card" data-action="reader" data-book="비가 그친 오후"><span class="continue-kicker">${icon(key==='night'?'moon':'book')}${t.card}</span><strong>비가 그친<br class="hero-break"> 오후</strong><span class="continue-description">창가에 남은 빛, 그다음 문장.</span><span class="continue-bottom"><span>42.3% <small>읽는 중</small></span><span class="continue-arrow">${icon('arrow')}</span></span><span class="progress-track"><span style="width:42.3%"></span></span><span class="hero-decoration" aria-hidden="true"></span></button>`;
  }
  const files = [ ['비가 그친 오후.txt','42.3% · 어제 읽음',true], ['숲으로 가는 길.txt','읽기 전',false], ['여름의 마지막 편지.txt','12.8% · 3일 전',false] ];
  function fileRows(onlyRecent = false) { return files.map(([title,meta,reading],i)=>onlyRecent && meta==='읽기 전' ? '' : `<div class="file-row ${reading?'is-reading':''}"><button class="file-open" data-action="reader" data-book="${esc(title.replace('.txt',''))}"><span class="file-icon">${icon('file')}<small>TXT</small></span><span class="file-details"><strong>${title}</strong><small>${meta}</small></span>${key==='archive'?'<span class="file-size">'+['1.2 MB','840 KB','2.4 MB'][i]+'</span>':''}</button>${ib('more','파일 정보: '+title,'file-info',`data-title="${esc(title)}"`)}</div>`).join(''); }
  function folder(s, recentOnly = false) {
    const inChild = s.child;
    let header = key==='archive' ? `<header class="archive-header"><div><span class="archive-mark">Y</span><strong>여백</strong></div>${ib('sliders','앱 설정','settings')}</header><div class="archive-location"><span class="tiny-label">${t.folderLabel}</span><h2>${icon('folder')}${esc(inChild || prefs.folder.split(' / ').at(-1))}</h2></div>` : `<header class="library-header"><div><span class="library-eyebrow">${t.folderLabel}</span><h2>여백<span class="brand-dot">.</span></h2></div>${ib(key==='night'?'moon':'sliders','앱 설정','settings')}</header>`;
    const breadcrumb = `<div class="breadcrumb">${action(icon('folder')+'<span>'+esc(prefs.folder)+'</span>','root-folder','path-button')}${inChild?'<span class="path-child">/ '+esc(inChild)+'</span>':''}${ib('refresh','폴더 새로고침','refresh')}</div>`;
    const folders = `<div class="folder-row">${['완결','메모'].map((name,i)=>action(`${icon('folder')}<span>${name}</span>${icon('arrow')}`,'child-folder','folder-tile',`data-folder="${name}"`)).join('')}</div>`;
    let content = recentOnly ? `${dividerTitle('최근 읽은 책','<span>읽던 위치 그대로</span>')}${fileRows(true)}` : key==='archive' ? `${breadcrumb}${dividerTitle('폴더와 파일','<span>폴더 우선 · 이름순</span>')}${inChild?'':folders}${fileRows()}${dividerTitle('이어 읽기')}${recentCard()}` : `${recentCard()}${breadcrumb}${dividerTitle(esc(inChild || '폴더와 파일'),'<span>이름순</span>')}${inChild?'':folders}${fileRows()}`;
    return `<div class="page library-page">${header}<div class="library-scroll">${content}</div>${nav(recentOnly?'recent':'folder')}</div>`;
  }
  function reader(s) {
    const title=s.book || '비가 그친 오후';
    return `<div class="page reader-page ${s.chrome===false?'chrome-hidden':''}" data-paper="${prefs.color}">
      <header class="reader-top">${ib('back','폴더로 돌아가기','folder')}<div><span>지금 읽는 책</span><strong>${esc(title)}</strong></div>${ib('bookmark',s.bookmarked?'책갈피 해제':'책갈피 저장','bookmark',`aria-pressed="${!!s.bookmarked}"`)}</header>
      <div class="reading-scroll" tabindex="0" aria-label="TXT 본문. 스크롤해서 읽기"><div class="chapter-kicker">첫 번째 이야기</div><h2>창가에 남은 빛</h2><div class="chapter-rule"></div><article class="prose ${prefs.font}" style="--font-size:${prefs.size}px;--line-height:${prefs.line};--text-margin:${prefs.margin}px">${sample.concat(sample).map((p,i)=>`<p ${i===2?'class="dialogue"':''}>${p}</p>`).join('')}</article></div>
      <button class="show-reader-tools" data-action="toggle-chrome" aria-label="독서 메뉴 표시">${icon('more')}</button>
      <footer class="reader-tools"><div class="reading-progress"><span class="progress-number">42.3%</span><input aria-label="읽기 진행률" type="range" min="0" max="100" step="0.1" value="42.3" data-range="progress"><span>100</span></div><div class="reader-actions">${action(icon('search')+'<span>검색</span>','search')}${action(icon('bookmark')+'<span>책갈피</span>','bookmarks')}${action('<span class="type-icon">가</span><span>읽기 설정</span>','options')}${ib('close','독서 메뉴 숨기기','toggle-chrome')}</div></footer>
    </div>`;
  }
  function settings() {
    return `<div class="page settings-page">${top('설정')}<div class="settings-scroll"><div class="settings-intro"><span class="tiny-label">나에게 맞는 여백</span><h3>읽는 자리를<br>정해 보세요.</h3></div>${dividerTitle('앱 설정','<span>앱 전체에 적용</span>')}
      <button class="start-folder-card" data-action="choose-folder"><span class="setting-symbol">${icon('folder')}</span><span><strong>시작 폴더</strong><span class="folder-value">${esc(prefs.folder)}</span><small>앱을 시작할 때 이 폴더를 엽니다.</small></span>${icon('arrow')}</button>
      <div class="setting-block"><div class="setting-label"><strong>앱 화면 테마</strong><span>서재와 메뉴</span></div><div class="segmented">${[['design','기본'],['light','밝게'],['dark','어둡게']].map(([v,label])=>pill(label,'app-theme',prefs.appColor===v,`data-value="${v}"`)).join('')}</div></div>
      ${dividerTitle('뷰어 설정','<span>파일 형식별로 적용</span>')}<button class="settings-link" data-action="options" data-from="settings"><span class="type-icon">가</span><span><strong>TXT 뷰어</strong><small>글꼴 · 크기 · 간격 · 본문 색상</small></span>${icon('arrow')}</button>
      <div class="setting-explanation">${icon('book')}<p>책을 읽다가 앱을 닫으면<br>다음 실행에 이어 읽기를 묻습니다.</p></div><div class="settings-signature">여백 <span>읽는 시간, 나만의 자리.</span></div></div>${nav('settings')}</div>`;
  }
  function options(s) {
    const fontText = prefs.size;
    return `${reader(s)}<div class="sheet-shade"></div><section class="option-sheet" aria-label="TXT 읽기 설정"><span class="sheet-handle"></span><header><div><span class="tiny-label">TXT 전용</span><h2>읽기 설정</h2></div>${ib('close','읽기 설정 닫기','close-options')}</header><div class="options-scroll">
      <div class="text-preview ${prefs.font}" data-paper="${prefs.color}" style="font-size:${Math.min(prefs.size,22)}px">어떤 이야기는<br>잠시 머물러도 좋았다.</div>
      <div class="setting-label"><strong>글꼴</strong></div><div class="segmented font-choices">${pill('고딕','font',prefs.font==='sans','data-value="sans"')}${pill('명조','font',prefs.font==='serif','data-value="serif"')}</div>
      <div class="setting-label size-setting"><strong>글자 크기</strong><div class="stepper">${action('−','size-down','',`aria-label="글자 크기 줄이기" ${fontText<=14?'disabled':''}`)}<output class="font-size-output">${fontText}</output>${action('+','size-up','',`aria-label="글자 크기 늘리기" ${fontText>=32?'disabled':''}`)}</div></div>
      <div class="slider-setting"><label>줄간격 <output data-output="line">${prefs.line.toFixed(2)}</output></label><input aria-label="줄간격" type="range" min="1.3" max="2.1" step="0.05" value="${prefs.line}" data-range="line"></div>
      <div class="slider-setting"><label>좌우 여백 <output data-output="margin">${prefs.margin}</output></label><input aria-label="좌우 여백" type="range" min="16" max="40" step="1" value="${prefs.margin}" data-range="margin"></div>
      <div class="setting-label"><strong>본문 색상</strong></div><div class="color-choices">${[['light','밝게'],['sepia','세피아'],['dark','어둡게']].map(([v,label])=>action(`<span class="color-dot ${v}">가${prefs.color===v?icon('check'):''}</span><span>${label}</span>`,'paper',prefs.color===v?'selected':'',`data-value="${v}" aria-pressed="${prefs.color===v}"`)).join('')}</div>
      ${action('TXT 설정 기본값 복원','reset-txt','reset-txt')}<p class="scope-note">시작 폴더와 독서 기록은 유지됩니다.</p></div></section>`;
  }
  function resume() {
    return `<div class="modal-shade"></div><section class="resume-dialog" role="dialog" aria-modal="true" aria-label="이어서 읽기 확인"><span class="resume-symbol">${icon(key==='night'?'moon':'book')}</span><span class="tiny-label">읽던 이야기가 있어요</span><h2>이어서<br class="resume-break"> 읽으시겠습니까?</h2><div class="resume-book"><span class="resume-file">${icon('file')}</span><div><strong>비가 그친 오후.txt</strong><span>마지막 읽은 위치 <b>42.3%</b></span></div></div><div class="resume-progress"><span></span></div><p>서두르지 않아도 괜찮아요.<br>마지막 문장에서 기다리고 있을게요.</p><div class="dialog-actions">${action('폴더 보기','folder','secondary-button')}${action('이어서 읽기 '+icon('arrow'),'reader','primary-button')}</div></section>`;
  }
  function popup(s) {
    if(!s.popup) return '';
    const close=ib('close','닫기','close-popup');
    let content='';
    if(s.popup==='folder') content=`<span class="tiny-label">시작 폴더 변경</span><h2>어디에서 읽을까요?</h2><p>앱을 새로 열 때 선택한 폴더가 열립니다.</p>${['Books / 소설','Books / 에세이','Documents / 읽을거리'].map(x=>action(icon('folder')+'<span>'+x+'</span>'+(prefs.folder===x?icon('check'):icon('arrow')),'select-folder','folder-pick',`data-value="${x}"`)).join('')}`;
    if(s.popup==='info') content=`<span class="tiny-label">파일 정보</span><h2>${esc(s.infoTitle||'비가 그친 오후.txt')}</h2><dl><dt>위치</dt><dd>${esc(prefs.folder)}</dd><dt>형식</dt><dd>TXT 텍스트</dd><dt>크기</dt><dd>1.2 MB</dd></dl><p>원본 파일은 연결된 폴더에 있습니다.</p>`;
    if(s.popup==='bookmarks') content=`<span class="tiny-label">이 책의 기록</span><h2>책갈피</h2><button class="bookmark-result" data-action="jump-bookmark"><span>42.3%</span><p>비가 그친 뒤의 골목은 평소보다 조금 넓어 보였다.</p>${icon('arrow')}</button>`;
    if(s.popup==='search') content=`<span class="tiny-label">TXT 본문에서 찾기</span><h2>검색</h2><label class="search-field">${icon('search')}<input type="search" aria-label="본문 검색어" placeholder="단어나 문장을 입력하세요" value="문장"></label><button class="search-result" data-action="jump-bookmark"><span>검색 결과</span><p>마지막으로 접어 둔 <mark>문장</mark> 옆에 손끝을 가만히 올려놓았다.</p>${icon('arrow')}</button>`;
    return `<div class="modal-shade"></div><section class="small-dialog" role="dialog" aria-modal="true">${close}${content}</section>`;
  }
  function applyPrefs(phone) {
    phone.dataset.appColor=prefs.appColor;
    phone.querySelectorAll('.reader-page,.text-preview').forEach(x=>x.dataset.paper=prefs.color);
    phone.querySelectorAll('.prose').forEach(x=>{x.style.setProperty('--font-size',prefs.size+'px');x.style.setProperty('--line-height',prefs.line);x.style.setProperty('--text-margin',prefs.margin+'px');});
  }
  function render(phone) {
    const s=states.get(phone);
    const contents=s.view==='reader'?reader(s):s.view==='options'?options(s):s.view==='settings'?settings():folder(s,s.view==='recent');
    phone.querySelector('.phone-app').innerHTML=contents+(s.view==='resume'?resume():'')+popup(s);
    applyPrefs(phone);
    if(s.scroll){const sc=phone.querySelector('.reading-scroll');if(sc)sc.scrollTop=s.scroll;}
  }
  document.querySelectorAll('.phone').forEach((phone,i)=>{states.set(phone,{view:views[i],initial:views[i],book:'비가 그친 오후',bookmarked:false,chrome:true,scroll:0});render(phone);});
  function rerender() { states.forEach((s,p)=>{const sc=p.querySelector('.reading-scroll');if(sc)s.scroll=sc.scrollTop;render(p);}); }
  function toast(phone,message) { phone.querySelector('.toast')?.remove();const el=document.createElement('div');el.className='toast';el.setAttribute('role','status');el.textContent=message;phone.querySelector('.phone-app').append(el);setTimeout(()=>el.remove(),2300); }
  document.addEventListener('click',e=>{
    const button=e.target.closest('[data-action]');if(!button)return;
    const act=button.dataset.action;
    if(act==='reset-all'){prefs={...defaults};save();states.forEach((s,p)=>{Object.assign(s,{view:s.initial,child:null,popup:null,chrome:true,bookmarked:false,scroll:0,book:'비가 그친 오후',optionsFrom:null,infoTitle:null});render(p);});return;}
    const phone=button.closest('.phone');if(!phone)return;
    const s=states.get(phone);const sc=phone.querySelector('.reading-scroll');if(sc)s.scroll=sc.scrollTop;
    if(['folder','recent','reader','settings'].includes(act)){s.view=act;s.popup=null;if(button.dataset.book)s.book=button.dataset.book;if(act==='reader')s.chrome=true;render(phone);return;}
    if(act==='options'){s.optionsFrom=button.dataset.from||'reader';s.view='options';render(phone);return;}
    if(act==='close-options'){s.view=s.optionsFrom==='settings'?'settings':'reader';render(phone);return;}
    if(act==='refresh'){toast(phone,'폴더가 최신 상태입니다.');return;}
    if(act==='child-folder'){s.child=button.dataset.folder;render(phone);toast(phone,button.dataset.folder+' 폴더');return;}
    if(act==='root-folder'){s.child=null;render(phone);return;}
    if(act==='choose-folder'){s.popup='folder';render(phone);return;}
    if(act==='select-folder'){prefs.folder=button.dataset.value;s.popup=null;states.forEach(x=>x.child=null);save();rerender();toast(phone,'시작 폴더를 변경했어요.');return;}
    if(act==='file-info'){s.popup='info';s.infoTitle=button.dataset.title;render(phone);return;}
    if(act==='close-popup'){s.popup=null;render(phone);return;}
    if(act==='bookmark'){s.bookmarked=!s.bookmarked;render(phone);toast(phone,s.bookmarked?'책갈피를 저장했어요.':'책갈피를 해제했어요.');return;}
    if(act==='bookmarks'||act==='search'){s.popup=act;render(phone);return;}
    if(act==='jump-bookmark'){s.popup=null;s.scroll=200;s.view='reader';render(phone);toast(phone,'해당 문장으로 이동했어요.');return;}
    if(act==='toggle-chrome'){s.chrome=s.chrome===false;phone.querySelector('.reader-page')?.classList.toggle('chrome-hidden',!s.chrome);return;}
    if(act==='size-down')prefs.size=Math.max(14,prefs.size-1);
    if(act==='size-up')prefs.size=Math.min(32,prefs.size+1);
    if(act==='font')prefs.font=button.dataset.value;
    if(act==='paper')prefs.color=button.dataset.value;
    if(act==='app-theme')prefs.appColor=button.dataset.value;
    if(act==='reset-txt'){const folder=prefs.folder,appColor=prefs.appColor;prefs={...defaults,folder,appColor};}
    save();rerender();
  });
  document.addEventListener('input',e=>{
    const range=e.target.closest('[data-range]');if(!range)return;
    const phone=range.closest('.phone');if(!phone)return;
    const name=range.dataset.range;
    if(name==='progress'){const value=Number(range.value);phone.querySelector('.progress-number').textContent=value.toFixed(1)+'%';const sc=phone.querySelector('.reading-scroll');if(sc)sc.scrollTop=(sc.scrollHeight-sc.clientHeight)*value/100;return;}
    prefs[name]=Number(range.value);save();
    states.forEach((s,p)=>{applyPrefs(p);const label=p.querySelector(`[data-output="${name}"]`);if(label)label.textContent=name==='line'?prefs.line.toFixed(2):prefs.margin;});
  });
  document.addEventListener('keydown',e=>{if(e.key!=='Escape')return;const phone=e.target.closest('.phone');if(!phone)return;const s=states.get(phone);if(s.popup)s.popup=null;else if(s.view==='resume')s.view='folder';else if(s.view==='options')s.view=s.optionsFrom==='settings'?'settings':'reader';render(phone);});
})();
