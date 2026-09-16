-- 云盘与相册备份系统 —— 建表脚本（MySQL 9.6, utf8mb4）
-- 说明：lower_case_table_names=1，表名/字段名一律小写；主键为雪花 id（应用侧生成）

CREATE DATABASE IF NOT EXISTS cloud_storage
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE cloud_storage;

-- ---------------------------------------------------------------------------
-- 物理对象：按内容寻址（sha256 + size），多用户/多文件复用同一份数据，靠引用计数决定生死
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_objects (
    id           BIGINT       NOT NULL COMMENT '主键（雪花）',
    bucket       VARCHAR(63)  NOT NULL DEFAULT 'cloud-storage' COMMENT '对象存储桶',
    object_key   VARCHAR(255) NOT NULL COMMENT '对象 key',
    storage_type VARCHAR(16)  NOT NULL DEFAULT 'minio' COMMENT 'minio / local',
    sha256       CHAR(64)     NOT NULL COMMENT '内容 sha256（十六进制小写）',
    size         BIGINT       NOT NULL COMMENT '字节数',
    content_type VARCHAR(128)          DEFAULT NULL,
    ref_count    INT          NOT NULL DEFAULT 0 COMMENT '引用计数，归零后由回收任务删除物理对象',
    status       VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT 'PENDING / READY / FAILED',
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_sha_size (sha256, size),
    KEY idx_object_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物理对象表（内容寻址 + 引用计数）';

-- ---------------------------------------------------------------------------
-- 用户文件：用户视角的文件条目，多个条目可指向同一物理对象（秒传/重复文件）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS files (
    id           BIGINT       NOT NULL COMMENT '主键（雪花）',
    user_id      BIGINT       NOT NULL COMMENT '归属用户',
    object_id    BIGINT       NOT NULL COMMENT '物理对象 id',
    file_name    VARCHAR(255) NOT NULL COMMENT '文件名',
    parent_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '父目录 id，0 = 根目录',
    size         BIGINT       NOT NULL COMMENT '字节数',
    sha256       CHAR(64)     NOT NULL COMMENT '内容 sha256，便于秒传与去重',
    content_type VARCHAR(128)          DEFAULT NULL,
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_files_user_parent (user_id, parent_id, deleted),
    KEY idx_files_sha (user_id, sha256),
    KEY idx_files_object (object_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户文件表';

-- ---------------------------------------------------------------------------
-- 上传会话：一次分片上传的完整状态（断点续传依赖它 + upload_parts）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_sessions (
    id           BIGINT       NOT NULL COMMENT '主键（雪花），同时作为对外的 uploadId',
    user_id      BIGINT       NOT NULL COMMENT '发起用户',
    device_id    VARCHAR(64)           DEFAULT NULL COMMENT '设备标识，用于限流与续传识别',
    file_name    VARCHAR(255) NOT NULL,
    file_size    BIGINT       NOT NULL,
    chunk_size   INT          NOT NULL COMMENT '分片大小（字节）',
    chunk_total  INT          NOT NULL COMMENT '分片总数',
    sha256       CHAR(64)     NOT NULL COMMENT '整文件 sha256，用于秒传与合并后校验',
    content_type VARCHAR(128)          DEFAULT NULL,
    parent_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '目标目录 id，0 = 根目录',
    status       VARCHAR(16)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/UPLOADING/MERGING/DONE/FAILED/EXPIRED',
    object_id    BIGINT                DEFAULT NULL COMMENT '合并成功后的物理对象 id',
    file_id      BIGINT                DEFAULT NULL COMMENT '合并成功后生成的文件 id',
    expire_time  DATETIME(3)  NOT NULL COMMENT '过期时间，超时由清理任务回收',
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_session_user_sha (user_id, sha256, file_size, status),
    KEY idx_session_expire (status, expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='分片上传会话表';

-- ---------------------------------------------------------------------------
-- 分片记录：(upload_id, part_no) 唯一，保证重复上传同一分片不产生脏数据（幂等）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS upload_parts (
    id          BIGINT      NOT NULL COMMENT '主键（雪花）',
    upload_id   BIGINT      NOT NULL COMMENT '上传会话 id',
    part_no     INT         NOT NULL COMMENT '分片序号，从 1 开始',
    part_size   BIGINT      NOT NULL COMMENT '本片字节数',
    sha256      CHAR(64)             DEFAULT NULL COMMENT '本片 sha256',
    etag        VARCHAR(128)         DEFAULT NULL COMMENT '对象存储返回的 etag',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_upload_no (upload_id, part_no),
    KEY idx_part_upload (upload_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='分片上传明细表';

-- ---------------------------------------------------------------------------
-- 用户存储配额：used_bytes 以 files 汇总为准（事实源），Redis 只做快路径预占
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quota_usage (
    id          BIGINT      NOT NULL COMMENT '主键（雪花）',
    user_id     BIGINT      NOT NULL COMMENT '用户 id',
    quota_bytes BIGINT      NOT NULL COMMENT '配额上限（字节）',
    used_bytes  BIGINT      NOT NULL DEFAULT 0 COMMENT '已用字节（含已提交文件）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota_user (user_id),
    KEY idx_quota_user (user_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户存储配额表';

-- ---------------------------------------------------------------------------
-- 异步处理任务：上传完成后由 MQ 驱动的流水线（缩略图 / EXIF / 以后的索引）
-- 这张表同时充当"本地消息表"：先落库再投递，投递失败由补偿任务扫出来重投
-- uk(file_id, task_type) 保证重复消费/重复投递都不会产生第二份任务（幂等）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processing_tasks (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    file_id         BIGINT       NOT NULL COMMENT '文件 id',
    object_id       BIGINT       NOT NULL COMMENT '物理对象 id',
    user_id         BIGINT       NOT NULL COMMENT '归属用户',
    task_type       VARCHAR(32)  NOT NULL COMMENT 'THUMBNAIL / EXIF / ...',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3)           DEFAULT NULL COMMENT '退避重试时间，到点由补偿任务重投',
    error_msg       VARCHAR(512)          DEFAULT NULL,
    result          VARCHAR(512)          DEFAULT NULL COMMENT '处理结果（如缩略图 objectKey、EXIF JSON）',
    cost_ms         BIGINT                DEFAULT NULL,
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_file_type (file_id, task_type),
    KEY idx_task_dispatch (status, next_retry_time),
    KEY idx_task_file (file_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='异步处理任务表（兼本地消息表）';
