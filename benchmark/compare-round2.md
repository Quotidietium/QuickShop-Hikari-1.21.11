# Benchmark comparison

Baseline: `results/baseline` (aggregated over forks by median)

| case | baseline ns/op | round2 ns/op | round2 delta |
|---|---:|---:|---:|
| db/insertShopChain | 730,844.3 | 733,490.9 | +0.4% |
| db/listShops | 2,768,589.0 | 2,588,700.0 | -6.5% |
| db/locateShopDataId | 30,432.8 | 31,427.2 | +3.3% |
| db/updateShop-unchangedData | 365,374.9 | 340,463.4 | -6.8% |
| economy/safeCommitNoTax | 71,188.0 | 68,309.9 | -4.0% |
| economy/safeCommitWithTax | 87,163.2 | 90,109.5 | +3.4% |
| lookup/getAllShops | 140,789.6 | 109,709.3 | -22.1% |
| lookup/getAllShopsByOwner | 274,928.9 | 119.2 | -100.0% |
| lookup/getShopById | 149,166.1 | 71.2 | -100.0% |
| lookup/getShopByLocation-hit | 4,337.0 | 5,089.0 | +17.3% |
| lookup/getShopByLocation-miss | 4,806.9 | 4,111.5 | -14.5% |
| lookup/getShopByRuntimeUuid-cached | 44,165.8 | 33,004.4 | -25.3% |
| lookup/getShopByRuntimeUuid-uncached | 75,157.8 | 77,127.2 | +2.6% |
| lookup/getShopsInChunk | 69.8 | 72.5 | +3.8% |
| serialize/createDataRecord | 130,824.8 | 129,555.0 | -1.0% |
| serialize/generateLookupParams | 758.7 | 754.0 | -0.6% |
| serialize/generateParams | 311.6 | 321.8 | +3.3% |
| text/findRelativeLanguages | 468.2 | 438.4 | -6.4% |
| text/forLocaleNoArgs | 19,259.0 | 18,045.4 | -6.3% |
| text/forLocaleWithArgs | 25,964.3 | 25,258.5 | -2.7% |
