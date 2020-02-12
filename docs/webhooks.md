# Webhooks

Webhooks to trigger builds on Jenkins.

## Configuration

### 1) via UI

To add/edit webhooks configuration navigate to *Repository setting* > *Webhooks* > *Create Webhooks*.

*Name*: Push: Jenkins XY - Generic Webhook Trigger  
*Events*: repo:refs_changed  
URL: https://jenkins-cl.se.com/XY/generic-webhook-trigger/invoke?token=repo_name_push

*Name*: Pull: Jenkins XY - Generic Webhook Trigger  
*Events*: pr:opened, pr:modified  
*URL*: https://jenkins-cl.se.com/XY/generic-webhook-trigger/invoke?token=repo_name_pull  

### 2) via REST api

GET configuration:
https://jira-cl.se.com/bitbucket/plugins/servlet/restbrowser#/resource/api-1-0-api-1-0-projects-projectkey-repos-repositoryslug-webhooks/GET

Result sample:

```
{
    "size": 2,
    "limit": 25,
    "isLastPage": true,
    "values": [
        {
            "id": 6,
            "name": "Push: Jenkins XY - Generic Webhook Trigger",
            "createdDate": 1562148853914,
            "updatedDate": 1562148853914,
            "events": [
                "repo:refs_changed"
            ],
            "configuration": {},
            "url": "https://jenkins-cl.se.com/XY/generic-webhook-trigger/invoke?token=bitbucket_training_push",
            "active": true
        },
        {
            "id": 7,
            "name": "Pull: Jenkins XY - Generic Webhook Trigger",
            "createdDate": 1562148853915,
            "updatedDate": 1562148853915,
            "events": [
                "pr:opened",
                "pr:modified"
            ],
            "configuration": {},
            "url": "https://jenkins-cl.se.com/XY/generic-webhook-trigger/invoke?token=bitbucket_training_pull",
            "active": true
        }
    ],
    "start": 0
}
```

Create webhook:  
https://jira-cl.se.com/bitbucket/plugins/servlet/restbrowser#/resource/api-1-0-api-1-0-projects-projectkey-repos-repositoryslug-webhooks/POST

```
POST 
https://jira-cl.se.com/bitbucket/rest/api/1.0/projects/{PROJECT_KEY}/repos/{REPO_SLUG}/webhooks
{
    "name": "Pull: Jenkins XY - Generic Webhook Trigger",
    "events": [
        "pr:opened",
        "pr:modified"
    ],
    "url": "https://jenkins-cl.se.com/XY/generic-webhook-trigger/invoke?token=XY_pull"
}
```


