# Benchmark comparison

Baseline: `results/baseline-final` (aggregated over forks by median)

| case | baseline ns/op | final ns/op | final delta |
|---|---:|---:|---:|
| db/insertShopChain | 655,222.5 | 411,912.7 | -37.1% |
| db/listShops | 2,614,601.0 | 1,612,584.6 | -38.3% |
| db/locateShopDataId | 28,100.6 | 113.7 | -99.6% |
| db/updateShop-unchangedData | 359,882.0 | 67,001.1 | -81.4% |
| economy/safeCommitNoTax | 57,660.4 | 55,980.8 | -2.9% |
| economy/safeCommitWithTax | 82,908.0 | 79,576.1 | -4.0% |
| lookup/getAllShops | 134,089.1 | 112,614.8 | -16.0% |
| lookup/getAllShopsByOwner | 282,270.5 | 111.0 | -100.0% |
| lookup/getShopById | 141,699.3 | 77.2 | -99.9% |
| lookup/getShopByLocation-hit | 4,375.7 | 4,161.9 | -4.9% |
| lookup/getShopByLocation-miss | 4,351.5 | 4,509.9 | +3.6% |
| lookup/getShopByRuntimeUuid-cached | 44,260.7 | 58,452.4 | +32.1% |
| lookup/getShopByRuntimeUuid-uncached | 78,129.4 | 60,714.1 | -22.3% |
| lookup/getShopsInChunk | 66.7 | 74.4 | +11.5% |
| serialize/createDataRecord | 121,721.1 | 59,877.5 | -50.8% |
| serialize/generateLookupParams | 712.8 | 661.6 | -7.2% |
| serialize/generateParams | 354.4 | 282.4 | -20.3% |
| text/findRelativeLanguages | 407.6 | 95.5 | -76.6% |
| text/forLocaleNoArgs | 18,765.4 | 232.6 | -98.8% |
| text/forLocaleWithArgs | 23,455.6 | 23,887.6 | +1.8% |
