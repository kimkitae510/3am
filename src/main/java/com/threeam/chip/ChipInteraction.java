package com.threeam.chip;

// 칩을 눌렀을 때 화면이 무엇을 하는가. 칩마다 폼을 따로 만들지 않으려고 둔 구분이다.
public enum ChipInteraction {

    // 누르면 label이 그대로 유저 메시지로 전송된다. 대부분의 칩.
    DIRECT,

    // 바로 보내지 않고 inputPreset의 입력 UI를 먼저 띄운다. 유저가 쓴 내용만 메시지가 된다 —
    // 칩 label을 먼저 보내면 "무슨 일이 있었나요?"를 되묻느라 한 턴이 통째로 날아간다.
    INPUT
}
