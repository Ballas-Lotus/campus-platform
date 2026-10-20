package com.campus.library.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

/**
 * 图书馆楼层（t_library_area，逸夫楼 5 层）。
 */
@Data
@TableName("t_library_area")
public class LibraryAreaDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String campus;

    private Integer floor;

    private LocalTime openTime;

    private LocalTime closeTime;

    private Integer seatCount;

    @TableLogic
    private Integer deleted;
}
