package com.binggr.glmall.ware.service.impl;

import com.binggr.common.constant.WareConstant;
import com.binggr.glmall.ware.service.WareSkuService;
import com.binggr.glmall.ware.vo.MergeVo;
import com.binggr.glmall.ware.entity.PurchaseDetailEntity;
import com.binggr.glmall.ware.service.PurchaseDetailService;
import com.binggr.glmall.ware.vo.PurchaseDoneVo;
import com.binggr.glmall.ware.vo.PurchaseItemDoneVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.binggr.common.utils.PageUtils;
import com.binggr.common.utils.Query;

import com.binggr.glmall.ware.dao.PurchaseDao;
import com.binggr.glmall.ware.entity.PurchaseEntity;
import com.binggr.glmall.ware.service.PurchaseService;
import org.springframework.transaction.annotation.Transactional;


@Service("purchaseService")
public class PurchaseServiceImpl extends ServiceImpl<PurchaseDao, PurchaseEntity> implements PurchaseService {

    @Autowired
    PurchaseDetailService detailService;

    @Autowired
    WareSkuService wareSkuService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                new QueryWrapper<PurchaseEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageUnreceive(Map<String, Object> params) {
        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                new QueryWrapper<PurchaseEntity>().eq("status",0).or().eq("status",1)
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void mergePurchase(MergeVo mergeVo) {
        Long purchaseId = mergeVo.getPurchaseId();
        if(purchaseId == null){
            //1???????????????
            PurchaseEntity purchaseEntity = new PurchaseEntity();

            purchaseEntity.setStatus(WareConstant.PurchaseStatusEnum.CREATED.getCode());
            purchaseEntity.setCreateTime(new Date());
            purchaseEntity.setUpdateTime(new Date());
            this.save(purchaseEntity);
            purchaseId = purchaseEntity.getId();
        }

        //TODO ???????????????????????????0???1 ???????????????

        List<Long> items = mergeVo.getItems();
        Long finalPurchaseId = purchaseId;
        List<PurchaseDetailEntity> collect = items.stream().map(i -> {
            PurchaseDetailEntity purchaseDetailEntity = new PurchaseDetailEntity();
            purchaseDetailEntity.setId(i);
            purchaseDetailEntity.setPurchaseId(finalPurchaseId);
            purchaseDetailEntity.setStatus(WareConstant.PurchaseDetailStatusEnum.ASSIGNED.getCode());
            return purchaseDetailEntity;
        }).collect(Collectors.toList());

        detailService.updateBatchById(collect);

        PurchaseEntity purchaseEntity = new PurchaseEntity();
        purchaseEntity.setId(purchaseId);
        purchaseEntity.setUpdateTime(new Date());
        this.updateById(purchaseEntity);
    }

    /**
     * ?????????
     * @param ids
     */
    @Override
    public void received(List<Long> ids) {
        //1??????????????????????????????????????????????????????
        List<PurchaseEntity> collect = ids.stream().map(id->{
            PurchaseEntity purchaseEntity = this.getById(id);
            return purchaseEntity;
        }).filter(item->{
            if(item.getStatus() == WareConstant.PurchaseStatusEnum.CREATED.getCode() ||
                    item.getStatus() == WareConstant.PurchaseStatusEnum.ASSIGNED.getCode()){
                return true;
            }else {
                return false;
            }
        }).map(item->{
            item.setStatus(WareConstant.PurchaseStatusEnum.RECEIVE.getCode());
            item.setUpdateTime(new Date());
            return item;
        }).collect(Collectors.toList());

        //2???????????????????????????
        this.updateBatchById(collect);

        //3???????????????????????????
        collect.forEach(item->{
            List<PurchaseDetailEntity> entities = detailService.listDetailByPurchaseId(item.getId());
            List<PurchaseDetailEntity> detailEntities = entities.stream().map(entity->{
                PurchaseDetailEntity purchaseDetailEntity = new PurchaseDetailEntity();
                purchaseDetailEntity.setId(entity.getId());
                purchaseDetailEntity.setStatus(WareConstant.PurchaseDetailStatusEnum.BUYING.getCode());
                return purchaseDetailEntity;
            }).collect(Collectors.toList());
            detailService.updateBatchById(detailEntities);
        });
    }

    @Transactional
    @Override
    public void done(PurchaseDoneVo doneVo) {
        //1????????????????????????
        Long id = doneVo.getId();

        //2????????????????????????
        Boolean flag = true;
        List<PurchaseItemDoneVo> items = doneVo.getItems();

        List<PurchaseDetailEntity> updates = new ArrayList<>();

        for (PurchaseItemDoneVo item : items) {
            PurchaseDetailEntity purchaseDetailEntity =  new PurchaseDetailEntity();
            if(item.getStatus() == WareConstant.PurchaseStatusEnum.HASERROR.getCode()){
                flag = false;
                purchaseDetailEntity.setStatus(item.getStatus());
            }else {
                purchaseDetailEntity.setStatus(WareConstant.PurchaseDetailStatusEnum.FINISH.getCode());
                //3?????????????????????????????????
                PurchaseDetailEntity entity = detailService.getById(item.getItemId());
                wareSkuService.addStock(entity.getSkuId(),entity.getWareId(),entity.getSkuNum());
            }
            purchaseDetailEntity.setId(item.getItemId());
            updates.add(purchaseDetailEntity);
        }
        detailService.updateBatchById(updates);

        //1????????????????????????
        PurchaseEntity purchaseEntity =  new PurchaseEntity();
        purchaseEntity.setId(id);
        purchaseEntity.setStatus(flag?WareConstant.PurchaseStatusEnum.FINISH.getCode():WareConstant.PurchaseStatusEnum.HASERROR.getCode());
        purchaseEntity.setUpdateTime(new Date());
        this.updateById(purchaseEntity);


    }


}