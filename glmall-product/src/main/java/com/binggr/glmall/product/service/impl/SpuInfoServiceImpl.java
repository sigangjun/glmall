package com.binggr.glmall.product.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.binggr.common.constant.ProductConstant;
import com.binggr.common.to.SkuHasStockVo;
import com.binggr.common.to.SkuReductionTo;
import com.binggr.common.to.SpuBounsTo;
import com.binggr.common.to.es.SkuEsModel;
import com.binggr.common.utils.R;
import com.binggr.glmall.product.entity.*;
import com.binggr.glmall.product.feign.CouponFeignService;
import com.binggr.glmall.product.feign.SearchFeignService;
import com.binggr.glmall.product.feign.WareFeignService;
import com.binggr.glmall.product.service.*;
import com.binggr.glmall.product.vo.*;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.binggr.common.utils.PageUtils;
import com.binggr.common.utils.Query;

import com.binggr.glmall.product.dao.SpuInfoDao;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.naming.directory.SearchControls;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {


    @Autowired
    SpuInfoDescService spuInfoDescService;

    @Autowired
    SpuImagesServiceImpl spuImagesService;

    @Autowired
    AttrService attrService;

    @Autowired
    ProductAttrValueService productAttrValueService;

    @Autowired
    SkuInfoService skuInfoService;

    @Autowired
    SkuImagesService skuImagesService;

    @Autowired
    SkuSaleAttrValueService skuSaleAttrValueService;

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    BrandService brandService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    WareFeignService wareFeignService;

    @Autowired
    SearchFeignService searchFeignService;


    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * ????????????
     * @GlobalTransactional
     * Seata AT ???????????????
     * @param vo
     */
    @GlobalTransactional
    @Transactional
    @Override
    public void savaSpuInfo(SpuSaveVo vo) {
        //1?????????spu???????????? pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo,spuInfoEntity);
        spuInfoEntity.setCreateTime(new Date());
        spuInfoEntity.setUpdateTime(new Date());
        this.savaBaseSpuInfo(spuInfoEntity);

        //2?????????spu??????????????? pms_spu_info_desc
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
        descEntity.setSpuId(spuInfoEntity.getId());
        descEntity.setDecript(String.join(",",decript));
        spuInfoDescService.savaSpuInfoDesc(descEntity);

        //3?????????spu???????????? pms_spu_images
        List<String> images = vo.getImages();
        spuImagesService.saveImages(spuInfoEntity.getId(),images);

        //4?????????spu??????????????? pms_product_attr_value
        List<BaseAttrs> baseAttrs =  vo.getBaseAttrs();
        List<ProductAttrValueEntity> collect = baseAttrs.stream().map(attr->{
            ProductAttrValueEntity productAttrValueEntity = new ProductAttrValueEntity();
            productAttrValueEntity.setAttrId(attr.getAttrId());
            AttrEntity attrEntity = attrService.getById(attr.getAttrId());
            productAttrValueEntity.setAttrName(attrEntity.getAttrName());
            productAttrValueEntity.setAttrValue(attr.getAttrValues());
            productAttrValueEntity.setQuickShow(attr.getShowDesc());
            productAttrValueEntity.setSpuId(spuInfoEntity.getId());

            return productAttrValueEntity;
        }).collect(Collectors.toList());

        productAttrValueService.saveProductAttr(collect);

        //5?????????spu???????????????glmall_sms sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBounsTo spuBounsTo = new SpuBounsTo();
        BeanUtils.copyProperties(bounds,spuBounsTo);
        spuBounsTo.setSpuId(spuInfoEntity.getId());
        R r =couponFeignService.saveSpuBounds(spuBounsTo);
        if(r.getCode() != 0){
            log.error("????????????spu??????????????????");
        }

        //5???????????????spu?????????sku??????
        List<Skus> skus = vo.getSkus();
        if(skus!=null && skus.size()>0){
           skus.forEach(item->{
               String defaultImg = "";
               for(Images image : item.getImages()){
                   if(image.getDefaultImg() == 1){
                       defaultImg = image.getImgUrl();
                   }
               }

               SkuInfoEntity infoEntity = new SkuInfoEntity();
               BeanUtils.copyProperties(item,infoEntity);
               infoEntity.setBrandId(spuInfoEntity.getBrandId());
               infoEntity.setCatalogId(spuInfoEntity.getCatalogId());
               infoEntity.setSaleCount(0L);
               infoEntity.setSpuId(spuInfoEntity.getId());
               infoEntity.setSkuDefaultImg(defaultImg);
               //5.1???sku???????????? pms_sku_info
               skuInfoService.saveSkuInfo(infoEntity);

               Long skuId = infoEntity.getSkuId();

               List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img->{
                   SkuImagesEntity imagesEntity = new SkuImagesEntity();
                   imagesEntity.setSkuId(skuId);
                   imagesEntity.setImgUrl(img.getImgUrl());
                   imagesEntity.setDefaultImg(img.getDefaultImg());
                   return imagesEntity;
               }).filter(entity->{
                   //??????true?????????????????????false????????????
                   return !StringUtils.isEmpty(entity.getImgUrl());
               }).collect(Collectors.toList());

               //5.2???sku???????????? pms_sku_images
               skuImagesService.saveBatch(imagesEntities);

               //5.3???sku????????????????????? pms_sku_sale_attr_value
               List<Attr> attrs =  item.getAttr();
               List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attrs.stream().map(attr -> {
                   SkuSaleAttrValueEntity saleAttrValueEntity = new SkuSaleAttrValueEntity();
                   BeanUtils.copyProperties(attr,saleAttrValueEntity);
                   saleAttrValueEntity.setSkuId(skuId);
                   return  saleAttrValueEntity;
               }).collect(Collectors.toList());
               skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);

               //5.4???sku??????????????????????????? sms_sku_full_reduction sms_sku_ladder sms_spu_bounds sms_member_price
               SkuReductionTo skuReductionTo = new SkuReductionTo();
               BeanUtils.copyProperties(item,skuReductionTo);
               skuReductionTo.setSkuId(skuId);
               if(skuReductionTo.getFullCount()>0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1){
                   R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
                   if(r1.getCode() !=0){
                       log.error("????????????sku??????????????????");
                   }
               }
           });
        }






    }

    @Override
    public void savaBaseSpuInfo(SpuInfoEntity spuInfoEntity) {
        this.baseMapper.insert(spuInfoEntity);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        QueryWrapper<SpuInfoEntity> queryWrapper = new QueryWrapper<>();

        String key = (String) params.get("key");
        if(!StringUtils.isEmpty(key)){
            queryWrapper.and((wrapper)->{
                wrapper.eq("id",key).or().like("spu_name",key);
            });
        }

        String status = (String) params.get("status");
        if(!StringUtils.isEmpty(status)){
            queryWrapper.eq("publish_status",status);

        }

        String brandId = (String) params.get("brandId");
        if(!StringUtils.isEmpty(brandId)  && !"0".equalsIgnoreCase(brandId)){
            queryWrapper.eq("brand_id",brandId);
        }

        String catelogId = (String) params.get("catelogId");
        if(!StringUtils.isEmpty(catelogId)  && !"0".equalsIgnoreCase(catelogId)){
            queryWrapper.eq("catalog_id",catelogId);
        }

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                queryWrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void up(Long spuId) {
        //????????????spuId??????sku????????????????????????
        List<SkuInfoEntity> skus = skuInfoService.getSkusBySpuId(spuId);
        List<Long> skuIdList = skus.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());

        //TODO 4???????????????sku???????????????????????????????????????
        List<ProductAttrValueEntity> baseAttrs = productAttrValueService.baseAttrlistforspu(spuId);
        List<Long> attrIds = baseAttrs.stream().map(attr->{
            return attr.getAttrId();
        }).collect(Collectors.toList());

        List<Long> searchAttrIds = attrService.selectSearchAttrIds(attrIds);

        Set<Long> idSet = new HashSet<>(searchAttrIds);

        List<SkuEsModel.Attrs> attrsList = baseAttrs.stream().filter(item->{
            return idSet.contains(item.getAttrId());
        }).map(item->{
            SkuEsModel.Attrs attrs1 = new SkuEsModel.Attrs();
            BeanUtils.copyProperties(item,attrs1);
            return attrs1;
        }).collect(Collectors.toList());

        //TODO 1?????????????????????????????????????????????????????????
        Map<Long, Boolean> stockMap = null;
        try{
           R r = wareFeignService.getSkuHasStock(skuIdList);
            TypeReference<List<SkuHasStockVo>> typeReference = new TypeReference<List<SkuHasStockVo>>() {};
            stockMap = r.getData(typeReference).stream().collect(Collectors.toMap(SkuHasStockVo::getSkuId, item -> item.getHasStock()));
        }catch (Exception e){
            log.error("??????????????????????????????:??????{}",e);
        }

        //????????????sku?????????
        Map<Long, Boolean> finalStockMap = stockMap;
        List<SkuEsModel> upProducts = skus.stream().map(sku->{
            //???????????????????????????
            SkuEsModel esModel = new SkuEsModel();
            BeanUtils.copyProperties(sku,esModel);
            esModel.setPrice(sku.getPrice());
            esModel.setSkuImage(sku.getSkuDefaultImg());

            if(finalStockMap ==null){
                esModel.setHasStock(true);
            }else{
                esModel.setHasStock(finalStockMap.get(sku.getSkuId()));
            }


            //TODO 2???????????????????????????0
            esModel.setHotScore(0L);

            //TODO 3?????????????????????????????????
            BrandEntity brand = brandService.getById(esModel.getBrandId());
            esModel.setBrandName(brand.getName());
            esModel.setBrandImg(brand.getLogo());

            CategoryEntity categoryEntity = categoryService.getById(esModel.getCatalogId());
            esModel.setCatalogName(categoryEntity.getName());

            //??????????????????
            esModel.setAttrs(attrsList);

            return esModel;
        }).collect(Collectors.toList());

        //TODO 5?????????????????????ES????????????
        R r = searchFeignService.productStatusUp(upProducts);
        if(r.getCode() == 0){
            //??????????????????
            //TODO 6???????????????spu?????????
            baseMapper.updateStatus(spuId, ProductConstant.StatusEnum.SPU_UP.getCode());
        }else{
            //??????????????????
            //TODO 7???????????????????????????????????????????????????XXX
            //Feign????????????
            /**
             * 1???????????????????????????????????????json
             *      RequestTemplate
             * 2???????????????????????????(?????????????????????????????????)
             *      excuteAndDecode
             * 3????????????????????????????????????
             */
        }

    }

    @Override
    public SpuInfoEntity getSpuInfoBySkuId(Long skuId) {
        SkuInfoEntity byId = skuInfoService.getById(skuId);
        Long spuId = byId.getSpuId();
        SpuInfoEntity spuInfoEntity = getById(spuId);
        return spuInfoEntity;
    }


}