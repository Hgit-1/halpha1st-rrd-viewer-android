/* Touch-sized controls over the existing eframe input contract. */
(function(root) {
  'use strict';
  const keys = {
    play: {key:' ',code:'Space',keyCode:32},
    back: {key:'ArrowLeft',code:'ArrowLeft',keyCode:37},
    step: {key:'ArrowRight',code:'ArrowRight',keyCode:39},
    restart: {key:'ArrowLeft',code:'ArrowLeft',keyCode:37,ctrlKey:true}
  };
  function install(canvas, bar, ready) {
    let point = null;
    canvas.addEventListener('pointerdown', e => {
      const r=canvas.getBoundingClientRect();
      point={x:(e.clientX-r.left)/r.width,y:(e.clientY-r.top)/r.height};
    });
    const hint=bar.querySelector('[data-hint]');
    function sendKey(action) {
      canvas.focus();
      const init=Object.assign({bubbles:true,cancelable:true},keys[action]);
      canvas.dispatchEvent(new KeyboardEvent('keydown',init));
      // Separate browser frames: egui must observe the pressed state.
      setTimeout(()=>canvas.dispatchEvent(new KeyboardEvent('keyup',init)),40);
    }
    bar.addEventListener('pointerdown',e=>e.preventDefault());
    bar.addEventListener('click',e=> {
      const button=e.target.closest('button'); if(!button)return;
      const action=button.dataset.action;
      if (!ready()) { hint.textContent='等待 Viewer 就绪'; return; }
      if(keys[action]) {
        sendKey(action);
        hint.textContent=action==='play'?'已发送播放/暂停；状态以时间线为准':'已发送时间线操作（不是图像帧步进）';
      } else if(action==='previous'||action==='next') {
        if(root.RerunAndroid && root.RerunAndroid.segmentStep) root.RerunAndroid.segmentStep(action==='next'?1:-1);
      } else if(action==='panels') {
        if(root.toggleCompactPanels)root.toggleCompactPanels();
      } else {
        if(!point){hint.textContent='先点一下要缩放的图像/3D区域，再按＋或－';return;}
        canvas.focus();
        const r=canvas.getBoundingClientRect();
        const pos={clientX:r.left+point.x*r.width,clientY:r.top+point.y*r.height,bubbles:true,cancelable:true};
        canvas.dispatchEvent(new MouseEvent('mousemove',pos));
        canvas.dispatchEvent(new WheelEvent('wheel',Object.assign({},pos,{deltaY:action==='in'?-22:22,deltaMode:0,ctrlKey:true})));
        hint.textContent='缩放最近点选位置；单指拖动平移，双击2D区域复位';
      }
    });
  }
  root.RerunControls={keys,install};
  if(typeof module!=='undefined')module.exports=root.RerunControls;
})(typeof window!=='undefined'?window:globalThis);
