/* Original vector marks. No font or network dependencies. */
window.YeobaekIcons = Object.freeze([
  {id:'01-open-book',number:'01',name:'펼친 책',english:'OPEN & QUIET',keyword:'책이라는 첫인상',description:'두 장의 종이 사이에 넉넉한 틈을 남겼습니다. 작은 크기에서도 전자책 앱이라는 뜻이 가장 빠르게 전해집니다.',tradeoff:'책 아이콘은 익숙한 만큼, 다른 독서 앱과의 차별성은 상대적으로 작습니다.',recommendation:'제품 성격을 가장 명확하게 전달',
    art:'<path d="M28 31C38 29 46 33 51 38V78C44 72 37 71 28 73Z"/><path class="secondary" d="M57 38C62 33 70 29 80 31V73C71 71 64 72 57 78Z"/>'},
  {id:'02-yeo-seal',number:'02',name:'여의 인장',english:'A NAME TO REMEMBER',keyword:'여백만의 이름',description:'‘여백’의 첫 글자 ‘여’를 둥근 획과 단정한 세로획으로 다시 그렸습니다. 이름을 기억하게 하는 작은 인장입니다.',tradeoff:'한글에 익숙하지 않은 사용자에게는 책이라는 의미가 바로 드러나지 않습니다.',recommendation:'브랜드의 고유성을 가장 잘 표현',
    art:'<ellipse cx="40" cy="54" rx="13" ry="18" fill="none" stroke="currentColor" stroke-width="5.8"/><path d="M72 28V80M59 44H72M59 62H72" fill="none" stroke="currentColor" stroke-width="5.8" stroke-linecap="square"/>'},
  {id:'03-kept-line',number:'03',name:'접어 둔 문장',english:'RIGHT WHERE YOU LEFT',keyword:'다시 이어 읽는 자리',description:'접힌 종이 안에 책갈피를 빈 공간으로 새겼습니다. 파일을 열고 읽던 자리로 돌아오는 앱의 동작을 담았습니다.',tradeoff:'메모·문서 앱으로도 보일 수 있어 앱 이름과 함께 사용할 때 의미가 더 분명합니다.',recommendation:'파일과 이어 읽기 기능을 연결',
    art:'<path fill-rule="evenodd" d="M30 27H64L78 41V81H30ZM47 45V71L55 65L63 71V45Z"/><path class="secondary" d="M64 27V41H78Z"/>'},
  {id:'04-comma',number:'04',name:'문장의 쉼표',english:'STAY A LITTLE LONGER',keyword:'서두르지 않는 독서',description:'머리부터 꼬리까지 하나의 윤곽으로 새로 그린 쉼표입니다. 통통한 머리에서 꼬리로 자연스럽게 가늘어지고, 안쪽 굴곡과 끝을 둥글게 마무리했습니다.',tradeoff:'분위기는 잘 전하지만, 아이콘만으로 전자책 기능을 설명하는 힘은 약합니다.',recommendation:'문학적인 분위기와 몰입을 강조',
    // One closed outline keeps the head, inner turn and tail continuous at small sizes.
    art:'<path d="M52 27C40 27 31 35 31 46C31 57 39 64 50 64C53 64 55 63.5 58 62.5C59.5 62 60.5 63.2 59.5 65C57 69 53 72 49 74C46 75.5 45 78 46.5 80C48 82 50.5 82 53 80.5C66.5 73 75 61.5 75 47C75 35 65 27 52 27Z"/>'},
  {id:'05-flowing-pages',number:'05',name:'흐르는 책장',english:'ONE LINE INTO THE NEXT',keyword:'부드러운 연속 스크롤',description:'겹친 책장의 곡선을 세 줄의 흐름으로 줄였습니다. 끊김 없이 다음 문장으로 이어지는 TXT 읽기를 표현합니다.',tradeoff:'추상적인 기호라 책보다 물결이나 오디오를 먼저 떠올릴 수 있습니다.',recommendation:'스크롤 경험을 감각적으로 표현',
    art:'<path d="M29 33C39 27 47 29 55 34S71 41 79 35M29 52C39 46 47 48 55 53S71 60 79 54" fill="none" stroke="currentColor" stroke-width="6.5" stroke-linecap="round"/><path class="secondary-stroke" d="M29 71C39 65 47 67 55 72S71 79 79 73" fill="none" stroke="currentColor" stroke-width="6.5" stroke-linecap="round"/>'}
]);
window.YeobaekIconPalette = Object.freeze({light:{background:'#536348',foreground:'#F8F5EF',secondary:'#C7D2B7'},dark:{background:'#292F25',foreground:'#E3EAD8',secondary:'#A9B99A'}});
window.yeobaekIconSvg = (id,mode='light',kind='full') => {
  const item=window.YeobaekIcons.find(x=>x.id===id)||window.YeobaekIcons[0];
  const palette=window.YeobaekIconPalette[mode]||window.YeobaekIconPalette.light;
  const foreground=kind==='monochrome'?'#000000':palette.foreground;
  const secondary=kind==='monochrome'?foreground:palette.secondary;
  const art=item.art.replaceAll('class="secondary"',`class="secondary" fill="${secondary}"`).replace(/<path class="secondary-stroke"[^>]*>/g,tag=>tag.replace('stroke="currentColor"',`stroke="${secondary}"`));
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="여백 · ${item.name}"><title>여백 · ${item.name}</title>${kind==='full'?`<rect width="108" height="108" fill="${palette.background}"/>`:''}<g class="icon-art" fill="${foreground}" color="${foreground}">${art}</g></svg>`;
};
