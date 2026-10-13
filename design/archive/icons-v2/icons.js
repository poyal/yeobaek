(() => {
  'use strict';
  const icons=window.YeobaekIcons;
  const svg=window.yeobaekIconSvg;
  const params=new URLSearchParams(location.search);
  let selected=icons.find(x=>x.id===params.get('icon'))?.id||icons[0].id;
  let mode=params.get('theme')==='dark'?'dark':'light';
  let mask='rounded';
  function render(){
    document.body.dataset.mode=mode;document.body.dataset.mask=mask;
    document.querySelectorAll('[data-mode-value]').forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.modeValue===mode)));
    document.querySelectorAll('[data-mask-value]').forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.maskValue===mask)));
    document.querySelector('#icon-grid').innerHTML=icons.map(item=>`<article class="candidate ${item.id===selected?'is-previewed':''}" data-candidate="${item.id}"><div class="candidate-top"><span class="candidate-number">${item.number}</span><span>${item.keyword}</span></div><div class="candidate-art"><div class="app-icon">${svg(item.id,mode)}</div></div><div class="candidate-copy"><h2>${item.name}</h2><p>${item.summary.replace('\n','<br>')}</p><div class="size-strip">${[48,32,24].map(size=>`<div><span class="app-icon" style="width:${size}px;height:${size}px">${svg(item.id,mode)}</span><small>${size}px</small></div>`).join('')}<div><span class="mono-mark">${svg(item.id,mode,'monochrome')}</span><small>단색</small></div></div><button class="preview-button" data-select="${item.id}" aria-pressed="${item.id===selected}">${item.id===selected?'아래 조합에 표시 중':'앱과 함께 보기'}<span>↗</span></button></div></article>`).join('');
    const item=icons.find(x=>x.id===selected);
    document.querySelector('#selected-number').textContent=`${item.number} / ${item.english}`;
    document.querySelector('#selected-icon').innerHTML=svg(item.id,mode);
    document.querySelector('#launcher-icon').innerHTML=svg(item.id,mode);
    document.querySelector('#selected-name').textContent=`${item.number}. ${item.name}`;
    document.querySelector('#selected-description').textContent=item.description;
    document.querySelector('#svg-link').href=`assets/${item.id}-${mode}.svg`;
    document.querySelector('#png-link').href=`previews/${item.id}-${mode}-1024.png`;
    document.querySelector('#context-image').src=`../../final/previews/${mode}-focus.png`;
    document.querySelector('#context-mode').textContent=mode.toUpperCase();
    document.querySelector('#context-link').href=`../../final/index.html?theme=${mode}`;
  }
  document.addEventListener('click',event=>{
    const button=event.target.closest('button');if(!button)return;
    let restore='';
    if(button.dataset.modeValue){mode=button.dataset.modeValue;restore=`[data-mode-value="${mode}"]`;}
    else if(button.dataset.maskValue){mask=button.dataset.maskValue;restore=`[data-mask-value="${mask}"]`;}
    else if(button.dataset.select){selected=button.dataset.select;restore=`[data-select="${selected}"]`;}
    else return;
    render();document.querySelector(restore)?.focus({preventScroll:true});
  });
  render();
})();
