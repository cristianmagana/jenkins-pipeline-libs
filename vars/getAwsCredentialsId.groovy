#!/usr/bin/groovy

def call(awxtianccount) {
    def awsCredentialsMap = [xtian_sbox: 'aws_xtian_sbox',
                             xtian_dev: 'aws_xtian_dev',
                             xtian_qa: 'aws_xtian_qa',
                             xtian_stg: 'aws_xtian_stg',
                             xtian_prod: 'aws_xtian_prod']
    awsCredentialsMap[awxtianccount]
}