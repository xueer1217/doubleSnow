-- doubleSnow 数据表 建立时间 2020/02/07


create
sequence user_info_uid_seq1 start
with 1000001 ;
create
sequence user_info_rid_seq1 start
with 5000001;
create
sequence login_event_id_seq start
with 2000001 increment by 1;
create
sequence attend_event_id_seq start
with 3000001 increment by 1;
create
sequence room_id_sequence start
with 4000001 increment by 1;

--用户信息表
create table user_info
(
    uid               bigint primary key    default nextval('user_info_uid_seq1'),
    user_name         varchar(100) not null,
    password          varchar(100) not null,
    roomId            bigint       not null default nextval('user_info_rid_seq1'), -- 暂时没用
    token             varchar(63)  not null default '',
    token_create_time bigint       not null,
    head_img          varchar(256) not null default '',
    cover_img         varchar(256) not null default '',                            -- 暂时没用
    email             varchar(256) not null default '',
    create_time       bigint       not null,
    rtmp_token        varchar(256) not null default '',
    sealed            BOOLEAN      NOT NULL DEFAULT FALSE,
    sealed_util_time  BIGINT       NOT NULL DEFAULT 0,
    allow_anchor      BOOLEAN      NOT NULL DEFAULT TRUE
);

alter
sequence user_info_uid_seq1 owned by user_info.uid;
alter
sequence user_info_rid_seq1 owned by user_info.roomId;
create unique index user_info_user_name_index on user_info (user_name);

--房间信息表
create table room
(
    roomId     bigint primary key    default nextval('room_id_sequence'), -- 一场会议对应一个roomId
    room_name  varchar(256) not null default '',                          -- 会议名称
    room_desc  varchar(256) not null default '',                          -- 会议描述
    cover_img  varchar(256) not null default '',                          -- 会议封面
    start_time bigint       not null,                                     -- 会议开始时间
    end_time   bigint       not null default 0,                           -- 会议结束时间
    anchorId   bigint       not null                                      -- 会议主持人id

);

alter
sequence room_id_sequence owned by room.roomId;

--登录事件表
create table login_event
(
    id         bigint primary key default nextval('login_event_id_seq'),
    uid        bigint                       not null, -- 登录用户id
    login_time bigint             default 0 not null  -- 登录时间
);

alter
sequence login_event_id_seq owned by login_event.id;


--参与事件表
create table attend_event
(
    id        bigint primary key default nextval('attend_event_id_seq'),
    uid       bigint                           not null,              -- 参与会议的用户id
    roomId    bigint                           not null,              -- 会议的房间号
    temporary boolean            default false not null,              -- 是否是中途退出？？
    in_time   bigint             default 0     not null,              -- 加入时间 （是加入时间为0表示未参与）
    out_time  bigint             default 0     not null               -- 退出时间
);

alter
sequence attend_event_id_seq owned by attend_event.id;

