# XTDB Datasets for XTDB Play

This directory contains scripts for generating and managing public datasets that can be used with XTDB Play.

## TPC-H Dataset Generation

The TPC-H benchmark provides a realistic dataset for testing database queries. We provide a script to generate TPC-H datasets at various scale factors and upload them to S3 for public access.

### Prerequisites

1. **AWS Credentials**: You need AWS credentials with write access to the S3 bucket configured in your environment:
   ```bash
   export AWS_ACCESS_KEY_ID=your_key_id
   export AWS_SECRET_ACCESS_KEY=your_secret_key
   export AWS_REGION=eu-west-1  # or your preferred region
   ```

2. **CloudFormation Stack**: Deploy the datasets bucket CloudFormation stack:
   ```bash
   aws cloudformation create-stack \
     --stack-name xt-play-datasets \
     --template-body file://cloudformation/05-datasets-bucket.yml \
     --region eu-west-1
   ```

### Generating TPC-H Dataset

#### Option 1: GitHub Action (Recommended)

The easiest way to generate a dataset is using the GitHub Action:

1. Go to the [Actions tab](https://github.com/xtdb/play/actions/workflows/generate-tpch.yml)
2. Click "Run workflow"
3. Enter the scale factor (e.g., `0.01`)
4. Enter the bucket name (default: `xtdb-play-datasets`)
5. Click "Run workflow"

The action will generate the dataset and upload it to S3 automatically.

#### Option 2: Local Generation

To generate a TPC-H dataset locally at scale factor 0.01:

```bash
clojure -M:generate-tpch xtdb-play-datasets 0.01 eu-west-1
```

**Arguments:**
- `bucket-name`: The S3 bucket to upload to (e.g., `xtdb-play-datasets`)
- `scale-factor`: The TPC-H scale factor (0.01 = ~10MB, 1.0 = ~1GB, etc.)
- `region`: AWS region where the bucket is located (e.g., `eu-west-1`)

**Note:** Local generation requires AWS credentials configured in your environment (see Prerequisites).

**What it does:**
1. Starts an XTDB node configured to use S3 as its object store
2. Generates TPC-H data using XTDB's datasets module
3. Calls `finishChunk` to ensure all data is persisted to S3
4. The dataset is now publicly accessible at: `s3://xtdb-play-datasets/tpch-sf0.01/`

### Using Datasets with XTDB Play Lambda

The Lambda already has S3 read access configured. To use a dataset:

1. Configure your XTDB node in the Lambda to point to the S3 bucket:
   ```clojure
   (def node-config
     {:storage
      {:object-store
       {:module 'xtdb.s3/s3-object-store
        :bucket "xtdb-play-datasets"
        :prefix "tpch-sf0.01/"}}
      :server {:port 5432}})
   ```

2. The Lambda will load the pre-existing dataset from S3 on startup

**Note**: This is intentionally decoupled from the main XTDB Play UI. It's designed as a standalone capability that can be used independently.

### Scale Factor Guide

| Scale Factor | Approximate Size | Use Case |
|--------------|------------------|----------|
| 0.01 | ~10 MB | Quick testing, demos |
| 0.1 | ~100 MB | Development |
| 1.0 | ~1 GB | Performance testing |
| 10.0 | ~10 GB | Stress testing |

**Recommendation**: Start with 0.01 for minimal Lambda /tmp usage and fast cold starts.

### TPC-H Schema

The generated dataset includes the following tables:
- `customer`: Customer information
- `lineitem`: Order line items
- `nation`: Nations/countries
- `orders`: Customer orders
- `part`: Parts/products
- `partsupp`: Parts supplied by suppliers
- `region`: Geographic regions
- `supplier`: Supplier information

### Example Queries

Once the dataset is loaded, you can run TPC-H benchmark queries. For example:

```sql
-- Query 1: Pricing Summary Report
SELECT
    l_returnflag,
    l_linestatus,
    SUM(l_quantity) AS sum_qty,
    SUM(l_extendedprice) AS sum_base_price,
    COUNT(*) AS count_order
FROM lineitem
WHERE l_shipdate <= DATE '1998-12-01' - INTERVAL '90' DAY
GROUP BY l_returnflag, l_linestatus
ORDER BY l_returnflag, l_linestatus;
```

### Troubleshooting

**Problem**: Script fails with authentication error (local generation)
- **Solution**: Ensure AWS credentials are properly configured in your environment, or use the GitHub Action instead

**Problem**: GitHub Action fails with authentication error
- **Solution**: Ensure the repository has AWS credentials configured in GitHub Secrets (`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`)

**Problem**: CloudFormation stack creation fails
- **Solution**: Check that the bucket name `xtdb-play-datasets` is available (S3 bucket names are globally unique)

**Problem**: Dataset generation is slow
- **Solution**: This is expected for larger scale factors. SF-0.01 should complete in under a minute, but larger datasets may take several minutes or more.

### Architecture

The architecture is intentionally generic and decoupled:

1. **S3 Bucket**: Publicly readable bucket for datasets
2. **Generation Script**: Standalone Clojure script that writes directly to S3
3. **Lambda Access**: Lambda has generic S3 read permissions (not coupled to specific bucket)
4. **No UI Changes**: Feature is completely independent of XTDB Play UI

This design allows the Lambda to read from any S3 bucket, not just the datasets bucket.
